package au.com.azwork.idm.graphentity.search

import au.com.azwork.idm.graphentity.config.EntityKind
import au.com.azwork.idm.graphentity.model.EntityDocument
import au.com.azwork.idm.graphentity.support.Fixtures
import spock.lang.Specification
import spock.lang.Unroll

/** Design §9.4 — scoping enumeration results to the requesting object class. */
class TypeScoperSpec extends Specification {

    @Unroll
    def "doc(archetypes=#arch, BaseType=#base) matches user? #user, account? #account"() {
        given:
        def doc = new EntityDocument(id: 'x', baseType: base, directArchetypes: arch)

        expect:
        TypeScoper.matches(doc, Fixtures.userType()) == user
        TypeScoper.matches(doc, Fixtures.accountType()) == account

        where:
        arch                    | base | user  | account
        ['Customer']            | 4    | true  | false
        ['Account']             | 2    | false | true
        ['Customer', 'VIP']     | 4    | true  | false   // extra archetypes do not exclude
        ['Customer']            | 2    | false | false   // BaseType must agree with entityKind
        []                      | 4    | false | false
        null                    | 4    | false | false
        ['Customer', 'Account'] | 4    | true  | false   // ambiguous archetypes: BaseType breaks the tie
        ['customer']            | 4    | false | false   // archetype comparison is case-sensitive
    }

    def "a type with no static archetypes matches on BaseType alone (archetype filtering cannot discriminate)"() {
        given:
        def t = Fixtures.userType()
        t.archetypes = []

        expect:
        TypeScoper.matches(new EntityDocument(id: 'x', baseType: 4, directArchetypes: ['Anything']), t)
        !TypeScoper.matches(new EntityDocument(id: 'x', baseType: 2, directArchetypes: ['Anything']), t)
    }

    def "all static archetypes must be present"() {
        given:
        def t = Fixtures.userType()
        t.archetypes = ['Customer', 'Person']

        expect:
        !TypeScoper.matches(new EntityDocument(id: 'x', baseType: 4, directArchetypes: ['Customer']), t)
        TypeScoper.matches(new EntityDocument(id: 'x', baseType: 4, directArchetypes: ['Person', 'Customer']), t)
    }

    def "a missing BaseType on the remote document does not match any type"() {
        expect:
        !TypeScoper.matches(new EntityDocument(id: 'x', baseType: null, directArchetypes: ['Customer']), Fixtures.userType())
    }
}
