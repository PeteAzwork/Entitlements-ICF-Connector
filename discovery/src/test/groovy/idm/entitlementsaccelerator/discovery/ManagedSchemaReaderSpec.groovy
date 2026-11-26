package idm.entitlementsaccelerator.discovery

import groovy.json.JsonSlurper
import spock.lang.Shared
import spock.lang.Specification

/** Design §12 — reading managed.json. */
class ManagedSchemaReaderSpec extends Specification {

    @Shared Map managed = new JsonSlurper().parse(new File('src/test/resources/managed-sample.json')) as Map

    def "reads every managed object type"() {
        expect:
        ManagedSchemaReader.read(managed)*.name == ['user', 'account', 'role']
    }

    def "domain properties exclude relationships, virtual, protected/password and _id"() {
        when:
        def user = ManagedSchemaReader.read(managed).find { it.name == 'user' }

        then:
        user.properties.keySet() == ['userName', 'givenName', 'sn', 'mail', 'telephoneNumber'] as Set
    }

    def "a multi-valued (array) property is excluded and recorded with a reason (P20: attributes are single-valued strings)"() {
        when:
        def user = ManagedSchemaReader.read(managed).find { it.name == 'user' }

        then:
        !user.properties.containsKey('aliases')
        user.excludedProperties.keySet() == ['aliases'] as Set
        user.excludedProperties.aliases.toLowerCase().contains('multi-valued')
    }

    def "virtual and relationship arrays are not reported as excluded attributes: they are excluded for their own reasons"() {
        when:
        def user = ManagedSchemaReader.read(managed).find { it.name == 'user' }

        then:
        !user.excludedProperties.containsKey('effectiveRoles')
        !user.excludedProperties.containsKey('owns')
        !user.excludedProperties.containsKey('reports')
    }

    def "relationship properties are surfaced with reverse name, collection and cardinality"() {
        when:
        def user = ManagedSchemaReader.read(managed).find { it.name == 'user' }

        then:
        user.relationships*.property == ['owns', 'manager', 'reports', 'orphanLink']
        with(user.relationships.find { it.property == 'owns' }) {
            reversePropertyName == 'ownedBy'
            resourceCollections == ['managed/account']
            multiValued
        }
        with(user.relationships.find { it.property == 'manager' }) {
            reversePropertyName == 'reports'
            !multiValued
        }
    }

    def "a relationship without reversePropertyName is read with a null reverse (flagged later by the generator)"() {
        expect:
        ManagedSchemaReader.read(managed).find { it.name == 'user' }.relationships.find { it.property == 'orphanLink' }.reversePropertyName == null
    }

    def "properties with a uniqueness policy are natural-key candidates (WS6.01: managed.json has no index declaration)"() {
        expect:
        ManagedSchemaReader.read(managed).find { it.name == 'user' }.naturalKeyCandidates == ['userName']
        ManagedSchemaReader.read(managed).find { it.name == 'account' }.naturalKeyCandidates == ['accountId']
    }

    def "a property without a uniqueness policy is not a natural-key candidate"() {
        expect:
        ManagedSchemaReader.read([objects: [[name: 't', schema: [properties: [code: [type: 'string', policies: [[policyId: 'required']]]]]]]])[0]
            .naturalKeyCandidates == []
    }

    def "a type with no relationships yields an empty list"() {
        expect:
        ManagedSchemaReader.read(managed).find { it.name == 'role' }.relationships == []
    }

    def "reads from a JSON string as well"() {
        expect:
        ManagedSchemaReader.read(new File('src/test/resources/managed-sample.json').text)*.name == ['user', 'account', 'role']
    }

    def "a document without an objects array is rejected"() {
        when:
        ManagedSchemaReader.read([notObjects: []])

        then:
        thrown(IllegalArgumentException)
    }
}
