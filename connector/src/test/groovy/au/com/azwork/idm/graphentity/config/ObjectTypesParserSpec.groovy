package au.com.azwork.idm.graphentity.config

import au.com.azwork.idm.graphentity.support.Fixtures
import groovy.json.JsonOutput
import org.identityconnectors.framework.common.exceptions.ConfigurationException
import spock.lang.Specification
import spock.lang.Unroll

/** Design §6: the objectTypes document. */
class ObjectTypesParserSpec extends Specification {

    def "parses the design document's example configuration from JSON"() {
        when:
        def types = ObjectTypesParser.parse(Fixtures.objectTypesJson())

        then:
        types.keySet() == ['user', 'account'] as Set
        with(types.user) {
            name == 'user'
            idmResource == 'managed/user'
            entityKind == EntityKind.SUBJECT
            idProperty == 'userName'
            attributes == ['name', 'mail', 'telephoneNumber']
            archetypes == ['Customer']
            archetypeProperty == null
            relationships.keySet() == ['owns'] as Set
            relationships.owns.remoteType == 'Owns'
            relationships.owns.direction == Direction.OUTBOUND
            relationships.owns.sync
            relationships.owns.explicit
            relationships.owns.relatedType == 'account'
            relationships.owns.reverseProperty == 'ownedBy'
        }
        with(types.account) {
            entityKind == EntityKind.RESOURCE
            idProperty == 'accountId'
            relationships.ownedBy.direction == Direction.INBOUND
            relationships.ownedBy.remoteType == 'Owns'
        }
    }

    def "parses from an already-decoded map identically"() {
        expect:
        ObjectTypesParser.parse(Fixtures.objectTypesMap()) == ObjectTypesParser.parse(Fixtures.objectTypesJson())
    }

    def "the type name is taken from the map key and the relationship property from its key"() {
        when:
        def types = ObjectTypesParser.parse(Fixtures.objectTypesMap())

        then:
        types.user.name == 'user'
        types.user.relationships.owns.property == 'owns'
    }

    def "optional blocks default to empty collections"() {
        given:
        def minimal = [thing: [idmResource: 'managed/thing', entityKind: 'resource', idProperty: 'thingId']]

        when:
        def types = ObjectTypesParser.parse(minimal)

        then:
        types.thing.attributes == []
        types.thing.archetypes == []
        types.thing.relationships == [:]
        types.thing.archetypeProperty == null
    }

    def "archetypeProperty is carried through when set"() {
        given:
        def m = Fixtures.objectTypesMap()
        m.user.archetypeProperty = 'segments'

        expect:
        ObjectTypesParser.parse(m).user.archetypeProperty == 'segments'
    }

    def "a relationship entry with sync:false is kept but marked not synced"() {
        given:
        def m = Fixtures.objectTypesMap()
        m.user.relationships.reports = [sync: false]

        when:
        def types = ObjectTypesParser.parse(m)

        then:
        !types.user.relationships.reports.sync
        types.user.syncedRelationships().keySet() == ['owns'] as Set
    }

    def "a relationship entry without remoteType/direction is kept as convention-derived (not explicit)"() {
        given:
        def m = Fixtures.objectTypesMap()
        m.user.relationships.manages = [relatedType: 'user', reverseProperty: 'managedBy']

        when:
        def types = ObjectTypesParser.parse(m)

        then:
        !types.user.relationships.manages.explicit
        types.user.relationships.manages.remoteType == null
        types.user.relationships.manages.direction == null
    }

    @Unroll
    def "missing required field '#field' on a type is a ConfigurationException naming the type and field"() {
        given:
        def m = Fixtures.objectTypesMap()
        m.user.remove(field)

        when:
        ObjectTypesParser.parse(m)

        then:
        def e = thrown(ConfigurationException)
        e.message.contains('user')
        e.message.contains(field)

        where:
        field << ['idmResource', 'entityKind', 'idProperty']
    }

    @Unroll
    def "invalid value for '#field' (#value) is rejected"() {
        given:
        def m = Fixtures.objectTypesMap()
        m.user[field] = value

        when:
        ObjectTypesParser.parse(m)

        then:
        thrown(ConfigurationException)

        where:
        field        | value
        'entityKind' | 'person'
        'idProperty' | ''
        'attributes' | 'name'          // must be a list
        'archetypes' | [1, 2]          // must be strings
        'attributes' | ['name', '']    // blank attribute name
    }

    def "a relationship entry with an unknown direction is rejected"() {
        given:
        def m = Fixtures.objectTypesMap()
        m.user.relationships.owns.direction = 'sideways'

        when:
        ObjectTypesParser.parse(m)

        then:
        def e = thrown(ConfigurationException)
        e.message.contains('owns')
    }

    def "a relationship entry declaring only one of remoteType/direction is rejected as half an override"() {
        given:
        def m = Fixtures.objectTypesMap()
        m.user.relationships.owns.remove('direction')

        when:
        ObjectTypesParser.parse(m)

        then:
        def e = thrown(ConfigurationException)
        e.message.contains('owns')
    }

    @Unroll
    def "malformed JSON input '#json' is a ConfigurationException, not a JsonException"() {
        when:
        ObjectTypesParser.parse(json)

        then:
        thrown(ConfigurationException)

        where:
        json << ['', '   ', 'not json', '[1,2]', '{"user": "nope"}']
    }

    def "null input is a ConfigurationException"() {
        when:
        ObjectTypesParser.parse((String) null)

        then:
        thrown(ConfigurationException)
    }

    def "an empty objectTypes document is rejected: the connector would expose no object classes"() {
        when:
        ObjectTypesParser.parse('{}')

        then:
        thrown(ConfigurationException)
    }

    def "type names are used verbatim as ICF object class names"() {
        given:
        def m = [Device: [idmResource: 'managed/device', entityKind: 'resource', idProperty: 'serial']]

        expect:
        ObjectTypesParser.parse(m).keySet() == ['Device'] as Set
    }

    def "domain attribute names colliding with connector-managed attributes are rejected"() {
        given:
        def m = Fixtures.objectTypesMap()
        m.user.attributes << reserved

        when:
        ObjectTypesParser.parse(m)

        then:
        def e = thrown(ConfigurationException)
        e.message.contains(reserved)

        where:
        reserved << ['directArchetypes', 'entityRelationships', 'lastEvaluated', '__UID__', '__NAME__', 'ID', 'id']
    }

    // ---------------------------------------------------------------- P19 scope archetypes

    def "two types with the same entityKind and different scope archetypes are accepted"() {
        given:
        def m = Fixtures.objectTypesMap()
        m.employee = [idmResource: 'managed/employee', entityKind: 'subject', idProperty: 'employeeId', archetypes: ['Employee']]

        expect:
        ObjectTypesParser.parse(m).keySet() == ['user', 'account', 'employee'] as Set
    }

    def "two types with the same entityKind sharing a scope archetype are rejected, naming both types and the archetype"() {
        given:
        def m = Fixtures.objectTypesMap()
        m.employee = [idmResource: 'managed/employee', entityKind: 'subject', idProperty: 'employeeId', archetypes: ['Customer', 'Staff']]

        when:
        ObjectTypesParser.parse(m)

        then:
        def e = thrown(ConfigurationException)
        e.message.contains('user')
        e.message.contains('employee')
        e.message.contains('Customer')
    }

    def "when types share an entityKind, each must have a scope archetype"() {
        given:
        def m = Fixtures.objectTypesMap()
        m.employee = [idmResource: 'managed/employee', entityKind: 'subject', idProperty: 'employeeId', archetypes: []]

        when:
        ObjectTypesParser.parse(m)

        then:
        def e = thrown(ConfigurationException)
        e.message.contains('employee')
    }

    def "a type alone in its entityKind may have no archetypes: objectClass alone scopes it"() {
        given:
        def m = Fixtures.objectTypesMap()
        m.account.archetypes = []

        expect:
        ObjectTypesParser.parse(m).account.archetypes == []
    }

    def "only the first archetype is the scope archetype: later ones may repeat across types"() {
        given:
        def m = Fixtures.objectTypesMap()
        m.user.archetypes = ['Customer', 'Person']
        m.employee = [idmResource: 'managed/employee', entityKind: 'subject', idProperty: 'employeeId', archetypes: ['Employee', 'Person']]

        expect:
        ObjectTypesParser.parse(m).size() == 3
    }

    def "round-trips through JSON without loss"() {
        given:
        def once = ObjectTypesParser.parse(Fixtures.objectTypesMap())

        expect:
        ObjectTypesParser.parse(JsonOutput.toJson(Fixtures.objectTypesMap())) == once
    }
}
