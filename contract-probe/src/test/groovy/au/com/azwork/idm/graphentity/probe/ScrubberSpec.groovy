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
}
