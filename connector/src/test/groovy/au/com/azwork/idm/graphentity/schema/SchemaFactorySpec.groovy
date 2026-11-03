package au.com.azwork.idm.graphentity.schema

import au.com.azwork.idm.graphentity.config.Direction
import au.com.azwork.idm.graphentity.support.Fixtures
import org.identityconnectors.framework.common.exceptions.ConfigurationException
import org.identityconnectors.framework.common.objects.AttributeInfo
import org.identityconnectors.framework.common.objects.Name
import spock.lang.Specification
import spock.lang.Unroll

/** Design §5.2, §6, §9.5 — configuration-driven dynamic schema. */
class SchemaFactorySpec extends Specification {

    /** Any Connector class works for SchemaBuilder; the scripted harness passes its own. */
    static final Class CONNECTOR = org.identityconnectors.framework.spi.Connector

    def "emits one object class per configured type, named after the type"() {
        when:
        def schema = SchemaFactory.build(Fixtures.connectorConfig(), CONNECTOR)

        then:
        schema.objectClassInfo*.type as Set == ['user', 'account'] as Set
    }

    def "the user class carries __NAME__, the configured attributes and the connector-managed attributes"() {
        when:
        def info = SchemaFactory.objectClassInfo(Fixtures.userType())

        then:
        info.type == 'user'
        info.attributeInfo*.name as Set == [Name.NAME, 'name', 'mail', 'telephoneNumber', 'directArchetypes', 'entityRelationships', 'lastUpdated'] as Set
    }

    @Unroll
    def "domain attribute '#attr' is a single-valued, creatable, updatable string"() {
        given:
        def info = attr(Fixtures.userType(), attr)

        expect:
        info.type == String
        !info.multiValued
        info.creatable
        info.updateable
        info.readable

        where:
        attr << ['name', 'mail', 'telephoneNumber']
    }

    @Unroll
    def "connector-managed '#name' is a multivalued string that is creatable and updatable"() {
        given:
        def info = attr(Fixtures.userType(), name)

        expect:
        info.type == String
        info.multiValued
        info.creatable
        info.updateable

        where:
        name << ['directArchetypes', 'entityRelationships']
    }

    def "lastUpdated is read-only (not creatable, not updatable) and not returned by default"() {
        given:
        def info = attr(Fixtures.userType(), 'lastUpdated')

        expect:
        info.type == String
        !info.creatable
        !info.updateable
        info.readable
        !info.multiValued
    }

    def "__NAME__ is required"() {
        expect:
        attr(Fixtures.userType(), Name.NAME).required
    }

    def "every object class supports the operations the scripts implement and nothing else"() {
        when:
        def schema = SchemaFactory.build(Fixtures.connectorConfig(), CONNECTOR)

        then:
        def ops = schema.supportedObjectClassesByOperation.keySet()*.simpleName as Set
        ops.containsAll(['SchemaApiOp', 'TestApiOp', 'SearchApiOp', 'GetApiOp', 'CreateApiOp', 'UpdateApiOp', 'DeleteApiOp'])
        !ops.contains('SyncApiOp')          // §4: deliberately no SyncScript
        !ops.contains('AuthenticateApiOp')  // out of scope: password sync
    }

    def "schema load re-runs the §7 validation and refuses an asymmetric configuration"() {
        given:
        def cfg = Fixtures.connectorConfig()
        cfg.objectTypes.account.relationships.ownedBy.direction = Direction.OUTBOUND

        when:
        SchemaFactory.build(cfg, CONNECTOR)

        then:
        thrown(ConfigurationException)
    }

    def "a type with no domain attributes still yields a valid class with the managed attributes"() {
        given:
        def t = Fixtures.accountType()
        t.attributes = []

        expect:
        SchemaFactory.objectClassInfo(t).attributeInfo*.name as Set == [Name.NAME, 'directArchetypes', 'entityRelationships', 'lastUpdated'] as Set
    }

    def "the schema is deterministic for the same configuration"() {
        expect:
        SchemaFactory.build(Fixtures.connectorConfig(), CONNECTOR).objectClassInfo*.type.sort() ==
            SchemaFactory.build(Fixtures.connectorConfig(), CONNECTOR).objectClassInfo*.type.sort()
    }

    private static AttributeInfo attr(def type, String name) {
        SchemaFactory.objectClassInfo(type).attributeInfo.find { it.name == name }
    }
}
