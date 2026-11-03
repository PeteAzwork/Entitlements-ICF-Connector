package au.com.azwork.idm.graphentity.discovery

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

    def "indexed properties are natural key candidates"() {
        expect:
        ManagedSchemaReader.read(managed).find { it.name == 'user' }.naturalKeyCandidates == ['userName']
        ManagedSchemaReader.read(managed).find { it.name == 'account' }.naturalKeyCandidates == ['accountId']
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
