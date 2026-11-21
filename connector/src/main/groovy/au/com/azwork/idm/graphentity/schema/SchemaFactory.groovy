package au.com.azwork.idm.graphentity.schema

import au.com.azwork.idm.graphentity.config.ConnectorConfig
import au.com.azwork.idm.graphentity.config.ObjectTypeConfig
import au.com.azwork.idm.graphentity.convention.RelationshipConvention
import org.identityconnectors.framework.common.exceptions.ConfigurationException
import org.identityconnectors.framework.common.objects.AttributeInfo
import org.identityconnectors.framework.common.objects.AttributeInfoBuilder
import org.identityconnectors.framework.common.objects.Name
import org.identityconnectors.framework.common.objects.ObjectClassInfo
import org.identityconnectors.framework.common.objects.ObjectClassInfoBuilder
import org.identityconnectors.framework.common.objects.Schema
import org.identityconnectors.framework.api.operations.APIOperation
import org.identityconnectors.framework.common.FrameworkUtil
import org.identityconnectors.framework.common.objects.OperationOptionInfo
import org.identityconnectors.framework.spi.operations.CreateOp
import org.identityconnectors.framework.spi.operations.DeleteOp
import org.identityconnectors.framework.spi.operations.SPIOperation
import org.identityconnectors.framework.spi.operations.SchemaOp
import org.identityconnectors.framework.spi.operations.SearchOp
import org.identityconnectors.framework.spi.operations.TestOp
import org.identityconnectors.framework.spi.operations.UpdateOp

/**
 * Builds the ICF schema from configuration (design §5.2, §6, §9.5): one object class per configured
 * type, carrying __NAME__, the configured domain attributes (string, single-valued, decision P20), and the
 * connector-managed attributes {@code directArchetypes} (multivalued string), {@code entityRelationships}
 * (multivalued string, values in the P17 shape) and {@code lastEvaluated} (read-only: the service's policy
 * evaluation time, not a modification time).
 * The §7 consistency validation is re-run here so an invalid configuration fails at schema load.
 *
 * <p>Each class is declared against the operations the scripts implement, named explicitly rather than taken
 * from the connector class: the scripted harness's class implements every operation, and {@code SchemaBuilder}
 * would offer whatever that class implements. So the Schema is assembled directly from Schema, Test,
 * Search (which also gives Get), Create, Update and Delete. Not Sync (§4: no SyncScript) and not Authenticate
 * (password sync is out of scope).
 */
class SchemaFactory {

    static final String DIRECT_ARCHETYPES = 'directArchetypes'
    static final String ENTITY_RELATIONSHIPS = 'entityRelationships'
    static final String LAST_EVALUATED = 'lastEvaluated'

    /** The SPI operations every object class supports. */
    static final List<Class<? extends SPIOperation>> SUPPORTED_OPERATIONS =
        [SchemaOp, TestOp, SearchOp, CreateOp, UpdateOp, DeleteOp].asImmutable()

    static Schema build(ConnectorConfig config, Class<?> connectorClass) {
        if (!config?.objectTypes) throw new ConfigurationException('objectTypes defines no object types, so there is no schema to build')
        RelationshipConvention.validate(config.objectTypes)
        if (connectorClass == null) throw new IllegalArgumentException('connectorClass is required')
        Set<ObjectClassInfo> classes = new LinkedHashSet<>(config.objectTypes.values().collect { objectClassInfo(it) })
        Map<Class<? extends APIOperation>, Set<ObjectClassInfo>> byOperation = new LinkedHashMap<>()
        SUPPORTED_OPERATIONS.each { Class<? extends SPIOperation> spi ->
            FrameworkUtil.spi2apis(spi).each { Class<? extends APIOperation> api -> byOperation[api] = classes }
        }
        new Schema(classes, Collections.<OperationOptionInfo> emptySet(), byOperation,
            Collections.<Class<? extends APIOperation>, Set<OperationOptionInfo>> emptyMap())
    }

    static ObjectClassInfo objectClassInfo(ObjectTypeConfig type) {
        ObjectClassInfoBuilder builder = new ObjectClassInfoBuilder().setType(type.name)
        builder.addAttributeInfo(Name.INFO)
        type.attributes.each { String attr -> builder.addAttributeInfo(AttributeInfoBuilder.build(attr, String)) }
        builder.addAttributeInfo(AttributeInfoBuilder.define(DIRECT_ARCHETYPES).setType(String).setMultiValued(true).build())
        builder.addAttributeInfo(AttributeInfoBuilder.define(ENTITY_RELATIONSHIPS).setType(String).setMultiValued(true).build())
        builder.addAttributeInfo(AttributeInfoBuilder.define(LAST_EVALUATED).setType(String)
            .setCreateable(false).setUpdateable(false).build())
        builder.build()
    }
}
