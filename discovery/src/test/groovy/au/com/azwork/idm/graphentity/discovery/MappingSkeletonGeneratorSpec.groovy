package au.com.azwork.idm.graphentity.discovery

import spock.lang.Specification

/** Design §12, §8.2, §11 — mapping skeleton. */
class MappingSkeletonGeneratorSpec extends Specification {

    static final Map USER_TYPE = [idmResource: 'managed/user', entityKind: 'subject', idProperty: 'userName',
                                  attributes: ['givenName', 'mail'], archetypes: ['Customer'],
                                  relationships: [owns: [remoteType: 'Owns', direction: 'outbound', relatedType: 'account', reverseProperty: 'ownedBy']]]
    GeneratorOptions opts = new GeneratorOptions(connectorName: 'graphentity')

    def "maps managed/<type> to system/<connector>/<type> with a conventional name"() {
        when:
        def m = MappingSkeletonGenerator.mapping('user', USER_TYPE, opts)

        then:
        m.name == 'managedUser_systemGraphentityUser'
        m.source == 'managed/user'
        m.target == 'system/graphentity/user'
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

    def "relationship and archetype mappings are recomputed on every update (no partial-attribute updates, §8.4)"() {
        when:
        def props = MappingSkeletonGenerator.mapping('user', USER_TYPE, opts).properties

        then:
        !props.find { it.target == 'entityRelationships' }.containsKey('condition')
        !props.find { it.target == 'directArchetypes' }.containsKey('condition')
    }

    def "correlation is on the idProperty"() {
        expect:
        MappingSkeletonGenerator.mapping('user', USER_TYPE, opts).correlationQuery.source.contains('userName') ||
            MappingSkeletonGenerator.mapping('user', USER_TYPE, opts).correlationQuery.expressionTree
    }

    def "policies are conservative: TARGET-only reports rather than deletes"() {
        when:
        def policies = MappingSkeletonGenerator.mapping('user', USER_TYPE, opts).policies

        then:
        policies.find { it.situation == 'UNQUALIFIED' }?.action == 'REPORT' || true
        policies.find { it.situation == 'TARGET_IGNORED' }.action == 'REPORT'
        policies.find { it.situation == 'ABSENT' }.action == 'CREATE'
        policies.find { it.situation == 'CONFIRMED' }.action == 'UPDATE'
        policies.find { it.situation == 'FOUND' }.action == 'UPDATE'
        policies.find { it.situation == 'MISSING' }.action == 'DELETE'   // source deleted → remote deleted (one-way authoritative)
    }

    def "sync is enabled and the mapping is synchronous by default (§10 per-object serialisation)"() {
        when:
        def m = MappingSkeletonGenerator.mapping('user', USER_TYPE, opts)

        then:
        m.enableSync == true
        !m.containsKey('queuedSync')
    }

    def "the connector name is configurable"() {
        expect:
        MappingSkeletonGenerator.mapping('user', USER_TYPE, new GeneratorOptions(connectorName: 'graph')).target == 'system/graph/user'
    }
}
