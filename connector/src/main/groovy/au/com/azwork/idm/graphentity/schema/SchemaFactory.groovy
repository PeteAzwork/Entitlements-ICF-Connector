package au.com.azwork.idm.graphentity.schema

import au.com.azwork.idm.graphentity.config.ConnectorConfig
import au.com.azwork.idm.graphentity.config.ObjectTypeConfig
import org.identityconnectors.framework.common.objects.ObjectClassInfo
import org.identityconnectors.framework.common.objects.Schema

/**
 * Builds the ICF schema from configuration (design §5.2, §6, §9.5): one object class per configured
 * type, carrying __NAME__, the configured domain attributes (string, single-valued, decision P20), and the
 * connector-managed attributes {@code directArchetypes} (multivalued string), {@code entityRelationships}
 * (multivalued string, values in the P17 shape) and {@code lastEvaluated} (read-only: the service's policy
 * evaluation time, not a modification time).
 * The §7 consistency validation is re-run here so an invalid configuration fails at schema load.
 */
class SchemaFactory {

    /** TODO WS1. */
    static Schema build(ConnectorConfig config, Class<?> connectorClass) {
        throw new UnsupportedOperationException('TODO WS1: SchemaFactory.build')
    }

    /** TODO WS1. */
    static ObjectClassInfo objectClassInfo(ObjectTypeConfig type) {
        throw new UnsupportedOperationException('TODO WS1: SchemaFactory.objectClassInfo')
    }
}
