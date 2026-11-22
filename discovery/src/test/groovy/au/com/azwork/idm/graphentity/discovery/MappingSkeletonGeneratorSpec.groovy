package au.com.azwork.idm.entitlementsaccelerator.discovery

import spock.lang.Specification

/** Design §12, §8.2, §11 — mapping skeleton. */
class MappingSkeletonGeneratorSpec extends Specification {

    static final Map USER_TYPE = [idmResource: 'managed/user', entityKind: 'subject', idProperty: 'userName',
                                  attributes: ['givenName', 'mail'], archetypes: ['Customer'],
                                  relationships: [owns: [remoteType: 'Owns', direction: 'outbound', relatedType: 'account', reverseProperty: 'ownedBy']]]
    GeneratorOptions opts = new GeneratorOptions(connectorName: 'entitlementsaccelerator')

    def "maps managed/<type> to system/<connector>/<type> with a conventional name"() {
        when:
        def m = MappingSkeletonGenerator.mapping('user', USER_TYPE, opts)

        then:
        m.name == 'managedUser_systementitlementsacceleratorUser'
        m.source == 'managed/user'
        m.target == 'system/entitlementsaccelerator/user'
    }

    def "__NAME__ is mapped from the idProperty"() {
        expect:
        MappingSkeletonGenerator.mapping('user', USER_TYPE, opts).properties.find { it.target == '__NAME__' }.source == 'userName'
    }

    def "every configured attribute gets a direct property mapping"() {
        expect:
        MappingSkeletonGenerator.mapping('user', USER_TYPE, opts).properties.findAll { it.target in ['givenName', 'mail'] }*.source == ['givenName', 'mail']
    }

    def "entityRelationships and directArchetypes are bound to the shared library transformations"() {
        when:
        def props = MappingSkeletonGenerator.mapping('user', USER_TYPE, opts).properties

        then:
        with(props.find { it.target == 'entityRelationships' }) {
            transform.type == 'text/javascript'
            transform.file == MappingSkeletonGenerator.LIBRARY_PATH || transform.source.contains('buildRelationships')
        }
        with(props.find { it.target == 'directArchetypes' }) {
            transform.file == MappingSkeletonGenerator.LIBRARY_PATH || transform.source.contains('buildArchetypes')
        }
    }

    def "the library transforms receive the whole source object: source is empty, not absent (IDM 7.5, WS8)"() {
        when:
        def props = MappingSkeletonGenerator.mapping('user', USER_TYPE, opts).properties

        then:
        props.find { it.target == 'entityRelationships' }.source == ''
        props.find { it.target == 'directArchetypes' }.source == ''
    }

    def "relationship and archetype mappings are recomputed on every update (no partial-attribute updates, §8.4)"() {
        when:
        def props = MappingSkeletonGenerator.mapping('user', USER_TYPE, opts).properties

        then:
        !props.find { it.target == 'entityRelationships' }.containsKey('condition')
        !props.find { it.target == 'directArchetypes' }.containsKey('condition')
    }

    def "correlation is a script query matching __NAME__ against the idProperty (as in config/sync-mapping-user.sample.json)"() {
        when:
        def q = MappingSkeletonGenerator.mapping('user', USER_TYPE, opts).correlationQuery

        then:
        q.type == 'text/javascript'
        q.source.contains('__NAME__')
        q.source.contains('source.userName')
    }

    def "every situation IDM can raise has an explicit policy (no reliance on defaults)"() {
        expect:
        MappingSkeletonGenerator.mapping('user', USER_TYPE, opts).policies*.situation as Set ==
            ['CONFIRMED', 'FOUND', 'ABSENT', 'AMBIGUOUS', 'MISSING', 'UNQUALIFIED', 'UNASSIGNED', 'TARGET_IGNORED',
             'SOURCE_IGNORED', 'SOURCE_MISSING', 'FOUND_ALREADY_LINKED', 'LINK_ONLY', 'ALL_GONE'] as Set
    }

    def "policies are conservative: TARGET-only reports rather than deletes"() {
        when:
        def policies = MappingSkeletonGenerator.mapping('user', USER_TYPE, opts).policies

        then:
        policies.find { it.situation == 'UNQUALIFIED' }.action == 'REPORT'
        policies.find { it.situation == 'TARGET_IGNORED' }.action == 'REPORT'
        policies.find { it.situation == 'ABSENT' }.action == 'CREATE'
        policies.find { it.situation == 'CONFIRMED' }.action == 'UPDATE'
        policies.find { it.situation == 'FOUND' }.action == 'UPDATE'
        policies.find { it.situation == 'SOURCE_MISSING' }.action == 'DELETE'   // source deleted in IDM → remote deleted (one-way authoritative; WS8.06)
        policies.find { it.situation == 'MISSING' }.action == 'CREATE'          // linked remote gone → re-created (design §11)
        policies.find { it.situation == 'UNASSIGNED' }.action == 'REPORT'       // orphans are reported, not deleted (P16)
    }

    def "sync is enabled and the mapping is synchronous by default (§10 per-object serialisation)"() {
        when:
        def m = MappingSkeletonGenerator.mapping('user', USER_TYPE, opts)

        then:
        m.enableSync == true
        !m.containsKey('queuedSync')
        m.taskThreads == 4   // conservative: IDM's default is 10 (design §10, WS9.02)
    }

    def "the connector name is configurable"() {
        expect:
        MappingSkeletonGenerator.mapping('user', USER_TYPE, new GeneratorOptions(connectorName: 'graph')).target == 'system/graph/user'
    }
}
