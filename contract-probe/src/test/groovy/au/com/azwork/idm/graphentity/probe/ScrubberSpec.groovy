package au.com.azwork.idm.graphentity.probe

import spock.lang.Specification

class ScrubberSpec extends Specification {

    Scrubber s = new Scrubber('ws0-probe-r1-')

    def "probe entities pass through unchanged"() {
        given:
        def doc = [ID: 'ws0-probe-r1-A', BaseType: 2, attributes: [ID: 'ws0-probe-r1-A', probe: 'ws0'], Relationships: [], directArchetypes: ['Ws0Probe']]

        expect:
        s.scrub(doc) == doc
    }

    def "foreign entities keep shape but lose IDs and values"() {
        when:
        def out = s.scrub([ID: 'Sanne', BaseType: 4, attributes: [ID: 'Sanne', name: 'Sanne Groen'],
                           Relationships: [[TargetID: 'Acct-1', type: 'Owns']], directArchetypes: ['Customer'], lastUpdated: '2026-07-06T00:00:00Z', note: 'free text'])

        then:
        out.ID == 'scrubbed-1'
        out.attributes == [ID: 'scrubbed-1', name: Scrubber.REDACTED]
        out.Relationships == [[TargetID: 'scrubbed-2', type: 'Owns']]
        out.BaseType == 4
        out.directArchetypes == ['Customer']
        out.lastUpdated == '2026-07-06T00:00:00Z'
        out.note == Scrubber.REDACTED
    }

    def "aliases are stable, so edges between scrubbed entities still line up"() {
        when:
        def a = s.scrub([ID: 'Sanne', Relationships: [[TargetID: 'Acct-1', type: 'Owns']]])
        def b = s.scrub([ID: 'Acct-1', Relationships: [[SourceID: 'Sanne', type: 'Owns']]])

        then:
        a.Relationships[0].TargetID == b.ID
        b.Relationships[0].SourceID == a.ID
    }

    def "a probe entity's edge to a foreign entity has the foreign ID aliased"() {
        expect:
        s.scrub([ID: 'ws0-probe-r1-A', Relationships: [[TargetID: 'Sanne', type: 'T']]]).Relationships[0].TargetID == 'scrubbed-1'
    }

    def "envelopes and lists are walked"() {
        expect:
        s.scrub([items: [[ID: 'Sanne'], [ID: 'ws0-probe-r1-A']], totalCount: 2]) == [items: [[ID: 'scrubbed-1'], [ID: 'ws0-probe-r1-A']], totalCount: 2]
    }

    def "foreign IDs in entity paths are replaced, probe IDs kept"() {
        expect:
        s.scrubPath('/admin/entities/Sanne%20Groen') == '/admin/entities/scrubbed-1'
        s.scrubPath('/admin/entities/ws0-probe-r1-a%2Fb') == '/admin/entities/ws0-probe-r1-a%2Fb'
        s.scrubPath('/admin/entities') == '/admin/entities'
        s.scrubPath('/admin/entities/') == '/admin/entities/'
    }

    def "real search results (uid, name, baseType, isArchetypes) are scrubbed"() {
        when:
        def out = s.scrub([results: [[uid: 'Cor', name: 'Cor', baseType: 'Subject', attributes: [Name: 'Cor Jette'],
                                      isArchetypes: ['Customer'], directArchetypes: [], lastEvaluated: '2025-12-22T00:48:10Z']],
                           pagedResultsCookie: null, totalResults: 1, hasMore: false])

        then:
        out.results[0] == [uid: 'scrubbed-1', name: 'scrubbed-1', baseType: 'Subject', attributes: [Name: Scrubber.REDACTED],
                           isArchetypes: ['Customer'], directArchetypes: [], lastEvaluated: '2025-12-22T00:48:10Z']
        out.totalResults == 1
    }

    def "the GET read model (id, Attributes objects, compound attributes, source/target relationships) is scrubbed"() {
        when:
        def out = s.scrub([id: 'Cor', baseType: 2, lastEvaluated: '2025-12-22T00:48:10Z', reconciliationStarted: null,
                           Attributes: [Name: [name: 'Name', type: 'string', value: 'Cor Jette']],
                           compoundAttributes: [Phones: [[name: 'Phone', type: 'string', value: '+61 400 000 000']]],
                           relationships: [[source: 'Cor', target: 'Acct-1', type: 'OWNS', sourceType: 'Subject']]])

        then:
        out.id == 'scrubbed-1'
        out.Attributes == [Name: [name: 'Name', type: 'string', value: Scrubber.REDACTED]]
        out.compoundAttributes == [Phones: [[name: 'Phone', type: 'string', value: Scrubber.REDACTED]]]
        out.relationships == [[source: 'scrubbed-1', target: 'scrubbed-2', type: 'OWNS', sourceType: 'Subject']]
        !out.toString().contains('Cor')
    }

    def "the write model (id, attributes, sourceID/targetID relationships) is scrubbed"() {
        expect:
        s.scrub([id: 'Cor', baseType: 2, attributes: [Name: 'Cor Jette'], relationships: [[sourceID: 'Cor', targetID: 'Acct-1', type: 'OWNS']]]) ==
            [id: 'scrubbed-1', baseType: 2, attributes: [Name: Scrubber.REDACTED], relationships: [[sourceID: 'scrubbed-1', targetID: 'scrubbed-2', type: 'OWNS']]]
    }

    def "fails closed: a result-list element with no recognisable ID is still scrubbed"() {
        expect:
        s.scrub([results: [[key: 'Cor', label: 'Cor Jette', baseType: 'Subject']]]).results[0] ==
            [key: Scrubber.REDACTED, label: Scrubber.REDACTED, baseType: 'Subject']
    }

    def "a probe entity in a result list is kept"() {
        expect:
        s.scrub([results: [[uid: 'ws0-probe-r1-A', attributes: [probe: 'ws0']]]]).results[0] == [uid: 'ws0-probe-r1-A', attributes: [probe: 'ws0']]
    }

    def "every ID segment of a relationship path is aliased; endpoint keywords and probe IDs are kept"() {
        expect:
        s.scrubPath('/admin/entities/Cor/relationships/Acct-1/OWNS') == '/admin/entities/scrubbed-1/relationships/scrubbed-2/scrubbed-3'
        s.scrubPath('/admin/entities/ws0-probe-r1-A/relationships/Cor') == '/admin/entities/ws0-probe-r1-A/relationships/scrubbed-1'
        s.scrubPath('/admin/entities/bulk') == '/admin/entities/bulk'
    }

    def "a filter's right-hand value is aliased when it is a foreign ID, redacted when it is attribute data, kept for probe values"() {
        expect:
        s.scrub([filter: [operation: 'EQUALS', left: 'ID', right: 'Cor']]).filter.right == 'scrubbed-1'
        s.scrub([filter: [operation: 'EQUALS', left: 'uid', right: 'Cor']]).filter.right == 'scrubbed-1'
        s.scrub([filter: [operation: 'CONTAINS', left: 'name', right: 'Cor Jette']]).filter.right == Scrubber.REDACTED
        s.scrub([filter: [operation: 'STARTSWITH', left: 'ID', right: 'ws0-probe-r1-stab-']]).filter.right == 'ws0-probe-r1-stab-'
        s.scrub([filter: [operation: 'AND', left: [operation: 'EQUALS', left: 'ID', right: 'Cor'], right: [operation: 'EQUALS', left: 'ID', right: 'ws0-probe-r1-A']]]).filter ==
            [operation: 'AND', left: [operation: 'EQUALS', left: 'ID', right: 'scrubbed-1'], right: [operation: 'EQUALS', left: 'ID', right: 'ws0-probe-r1-A']]
    }

    def "manifests keep archetype names only; decisions, paths, instance lists and constraints are dropped"() {
        given:
        def body = [[name: 'm', subjectArchetypes: [[name: 'Customer', eligibilityDecisions: [[x: 1]], reconciliationPath: 'p', subjects: [[name: 'Cor']]]],
                     resourceArchetypes: null, constraints: [[name: 'c', rules: 'Cor may not']]]]

        expect:
        s.scrubResponse('/admin/manifests', body) == [[name: 'm', subjectArchetypes: [[name: 'Customer']], resourceArchetypes: null, constraints: Scrubber.REDACTED]]
        s.scrubResponse('/admin/manifests', [title: 'Not Found', status: 404, detail: 'Cor']) == [title: 'Not Found', status: 404, detail: Scrubber.REDACTED]
        s.scrubResponse('/admin/entities/search', [results: [[uid: 'Cor']]]) == [results: [[uid: 'scrubbed-1']]]
    }

    def "the search endpoint is not mistaken for an entity ID"() {
        expect:
        s.scrubPath('/admin/entities/search') == '/admin/entities/search'
        new Scrubber('p-', ['/admin/find']).scrubPath('/admin/find') == '/admin/find'
    }
}
