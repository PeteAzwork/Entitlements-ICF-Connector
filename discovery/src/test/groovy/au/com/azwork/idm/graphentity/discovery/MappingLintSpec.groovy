package au.com.azwork.idm.graphentity.discovery

import spock.lang.Specification

/** WS6.09 — the §8.4/§15 review check on mappings that target the connector. */
class MappingLintSpec extends Specification {

    static Map mapping(List properties, String target = 'system/graphentity/user') {
        [name: 'm', source: 'managed/user', target: target, properties: properties]
    }

    static final Map REL = [target: 'entityRelationships', transform: [type: 'text/javascript', source: 'x']]
    static final Map ARCH = [target: 'directArchetypes', transform: [type: 'text/javascript', source: 'x']]

    def "a mapping with both, unconditional, passes"() {
        expect:
        MappingLint.check(mapping([REL, ARCH])) == []
    }

    def "a missing #missing is reported"() {
        expect:
        MappingLint.check(mapping(present)).any { it.contains(missing) && it.contains('no property mapping') }

        where:
        missing               | present
        'entityRelationships' | [ARCH]
        'directArchetypes'    | [REL]
    }

    def "a condition on either is reported"() {
        expect:
        MappingLint.check(mapping([REL + [condition: 'x'], ARCH])).any { it.contains('entityRelationships') && it.contains('condition') }
    }

    def "mappings to other targets are ignored, and a whole sync.json is checked mapping by mapping"() {
        expect:
        MappingLint.check([mappings: [mapping([], 'system/ldap/account'), mapping([REL, ARCH])]]) == []
        MappingLint.check([mappings: [mapping([REL, ARCH]), mapping([REL])]]).size() == 1
    }

    def "the connector name is configurable"() {
        expect:
        MappingLint.check(mapping([], 'system/graph/user'), 'graph').size() == 2
        MappingLint.check(mapping([], 'system/graph/user')) == []
    }
}
