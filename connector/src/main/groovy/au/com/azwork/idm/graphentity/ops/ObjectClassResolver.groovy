package au.com.azwork.idm.graphentity.ops

import au.com.azwork.idm.graphentity.config.ConnectorConfig
import au.com.azwork.idm.graphentity.config.ObjectTypeConfig
import org.identityconnectors.framework.common.objects.ObjectClass

/** Maps an ICF object class to its configured type; unknown classes fail fast. */
class ObjectClassResolver {

    /** Throws ConnectorException naming the object class and the configured ones. TODO WS1. */
    static ObjectTypeConfig resolve(ObjectClass objectClass, ConnectorConfig config) {
        throw new UnsupportedOperationException('TODO WS1: ObjectClassResolver.resolve')
    }
}
