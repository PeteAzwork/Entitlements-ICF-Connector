package au.com.azwork.idm.graphentity.discovery

import au.com.azwork.idm.graphentity.config.ObjectTypesParser
import au.com.azwork.idm.graphentity.convention.RelationshipConvention
import groovy.json.JsonSlurper
import spock.lang.Specification

/** Design §12 — objectTypes generation and validation at authoring time. */
class ObjectTypesGeneratorSpec extends Specification {

    static List<ManagedType> getTypes() {
        ManagedSchemaReader.read(new JsonSlurper().parse(new File('src/test/resources/managed-sample.json')) as Map)
    }

    GeneratorOptions opts = new GeneratorOptions(selectedTypes: ['user', 'account'],
        entityKinds: [user: 'subject', account: 'resource'], idProperties: [user: 'userName', account: 'accountId'],
        archetypes: [user: ['Customer'], account: ['Account']],
        relationshipOverrides: [user: [manager: [remoteType: 'ReportsTo', direction: 'outbound'], reports: [remoteType: 'ReportsTo', direction: 'inbound']]])

    def "generates one block per selected type only"() {
        expect:
        ObjectTypesGenerator.generate(types, opts).objectTypes.keySet() == ['user', 'account'] as Set
    }

    def "the user block carries idmResource, entityKind, idProperty, attributes and archetypes"() {
        when:
        def user = ObjectTypesGenerator.generate(types, opts).objectTypes.user

        then:
        user.idmResource == 'managed/user'
        user.entityKind == 'subject'
        user.idProperty == 'userName'
        user.attributes == ['givenName', 'sn', 'mail', 'telephoneNumber']   // idProperty is the ID, not an attribute
        user.archetypes == ['Customer']
    }

    def "every relationship is listed with convention-derived values plus relatedType and reverseProperty"() {
        when:
        def user = ObjectTypesGenerator.generate(types, opts).objectTypes.user

        then:
        user.relationships.owns == [remoteType: 'Owns', direction: 'outbound', relatedType: 'account', reverseProperty: 'ownedBy']
    }

    def "the counterpart side is generated inbound with the same remoteType"() {
        when:
        def account = ObjectTypesGenerator.generate(types, opts).objectTypes.account

        then:
        account.relationships.ownedBy == [remoteType: 'Owns', direction: 'inbound', relatedType: 'user', reverseProperty: 'owns']
    }

    def "explicit overrides are applied verbatim"() {
        when:
        def user = ObjectTypesGenerator.generate(types, opts).objectTypes.user

        then:
        user.relationships.manager.remoteType == 'ReportsTo'
        user.relationships.manager.direction == 'outbound'
        user.relationships.reports.direction == 'inbound'
    }

    def "a pair the convention cannot resolve and no override is an error naming both properties"() {
        given:
        opts.relationshipOverrides = [:]

        when:
        def r = ObjectTypesGenerator.generate(types, opts)

        then:
        !r.valid
        r.errors.any { it.contains('manager') && it.contains('reports') }
    }

    def "a relationship without a reverse property violates A2 and is excluded with sync:false and a warning"() {
        when:
        def r = ObjectTypesGenerator.generate(types, opts)

        then:
        r.objectTypes.user.relationships.orphanLink.sync == false
        r.warnings.any { it.contains('orphanLink') && it.toLowerCase().contains('reverse') }
    }

    def "a relationship whose related type is not selected is kept but flagged"() {
        given:
        opts.selectedTypes = ['user']
        opts.relationshipOverrides.user.owns = [remoteType: 'Owns', direction: 'outbound']

        when:
        def r = ObjectTypesGenerator.generate(types, opts)

        then:
        r.objectTypes.user.relationships.owns.relatedType == 'account'
        r.warnings.any { it.contains('account') }
    }

    def "a missing entityKind for a selected type is an error"() {
        given:
        opts.entityKinds.remove('account')

        expect:
        ObjectTypesGenerator.generate(types, opts).errors.any { it.contains('account') && it.contains('entityKind') }
    }

    def "a selected type that does not exist in managed.json is an error"() {
        given:
        opts.selectedTypes << 'device'

        expect:
        ObjectTypesGenerator.generate(types, opts).errors.any { it.contains('device') }
    }

    def "defaultIdProperty prefers the natural key, then a userName-like name, else null"() {
        expect:
        ObjectTypesGenerator.defaultIdProperty(types.find { it.name == 'user' }) == 'userName'
        ObjectTypesGenerator.defaultIdProperty(types.find { it.name == 'account' }) == 'accountId'
        ObjectTypesGenerator.defaultIdProperty(types.find { it.name == 'role' }) == null
    }

    def "an unprompted idProperty with no default is an error (A3 requires human confirmation)"() {
        given:
        opts.selectedTypes = ['role']; opts.entityKinds = [role: 'resource']; opts.idProperties = [:]

        expect:
        ObjectTypesGenerator.generate(types, opts).errors.any { it.contains('role') && it.contains('idProperty') }
    }

    def "archetypes default to the capitalised type name"() {
        given:
        opts.archetypes = [:]

        expect:
        ObjectTypesGenerator.generate(types, opts).objectTypes.user.archetypes == ['User']
    }

    def "the generated document parses and validates with the connector's own parser and §7 validation"() {
        when:
        def parsed = ObjectTypesParser.parse(ObjectTypesGenerator.generate(types, opts).objectTypes)

        then:
        RelationshipConvention.validate(parsed) == []
    }

    def "generation is deterministic"() {
        expect:
        ObjectTypesGenerator.generate(types, opts).objectTypes == ObjectTypesGenerator.generate(types, opts).objectTypes
    }

    def "a same-type pair (manager/reports on user) produces both sides on the one type"() {
        when:
        def user = ObjectTypesGenerator.generate(types, opts).objectTypes.user

        then:
        user.relationships.manager.relatedType == 'user'
        user.relationships.reports.relatedType == 'user'
    }
}
