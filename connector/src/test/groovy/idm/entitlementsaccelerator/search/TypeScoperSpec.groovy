package idm.entitlementsaccelerator.search

import idm.entitlementsaccelerator.model.EntityDocument
import idm.entitlementsaccelerator.support.Fixtures
import spock.lang.Specification
import spock.lang.Unroll

/** Design §9.4; decision P19 as amended 2026-11-21 — scoping service entities to an IDM object type. */
class TypeScoperSpec extends Specification {

    static EntityDocument doc(Integer baseType, List<String> direct, List<String> is = []) {
        new EntityDocument(id: 'x', baseType: baseType, directArchetypes: direct, isArchetypes: is)
    }

    def "a type's scope archetype is its first static archetype"() {
        given:
        def t = Fixtures.userType()
        t.archetypes = ['Customer', 'Person']

        expect:
        TypeScoper.scopeArchetype(t) == 'Customer'
        TypeScoper.scopeArchetype(Fixtures.accountType()) == 'Account'
    }

    def "a type without static archetypes has no scope archetype"() {
        given:
        def t = Fixtures.accountType()
        t.archetypes = []

        expect:
        TypeScoper.scopeArchetype(t) == null
    }

    @Unroll
    def "enumeration scope: base type #base, direct #direct, policy #is → user #user, account #account"() {
        expect:
        TypeScoper.inEnumerationScope(doc(base, direct, is), Fixtures.userType()) == user
        TypeScoper.inEnumerationScope(doc(base, direct, is), Fixtures.accountType()) == account

        where:
        base | direct                  | is           | user  | account
        4    | ['Customer']            | []           | true  | false
        2    | ['Account']             | []           | false | true
        4    | ['VIP', 'Customer']     | []           | true  | false   // extra archetypes don't exclude
        4    | ['VIP']                 | []           | false | false   // scope archetype missing
        2    | ['Customer']            | []           | false | false   // base type disagrees
        4    | []                      | ['Customer'] | true  | false   // search reports direct archetypes here (C9; P19 amended)
        4    | null                    | ['Customer'] | true  | false   // the usual search item: directArchetypes unknown
        2    | null                    | ['Account']  | false | true
        4    | null                    | ['VIP']      | false | false
        4    | null                    | null         | false | false
        4    | null                    | []           | false | false
        4    | ['customer']            | []           | false | false   // case-sensitive
    }

    def "a type with no scope archetype matches on base type alone"() {
        given:
        def t = Fixtures.accountType()
        t.archetypes = []

        expect:
        TypeScoper.inEnumerationScope(doc(2, []), t)
        !TypeScoper.inEnumerationScope(doc(4, []), t)
    }

    @Unroll
    def "read-by-ID scope checks the base type only: base #base, direct #direct → user #user"() {
        expect:
        TypeScoper.inByIdScope(doc(base, direct), Fixtures.userType()) == user

        where:
        base | direct       | user
        4    | ['Customer'] | true
        4    | []           | true    // found for correlation; the next update stamps the archetype
        4    | null         | true    // the read model carries no archetypes
        2    | ['Customer'] | false
        null | ['Customer'] | false
    }
}
