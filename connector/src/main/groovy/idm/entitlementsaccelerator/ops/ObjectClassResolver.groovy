package idm.entitlementsaccelerator.ops

import idm.entitlementsaccelerator.config.ConnectorConfig
import idm.entitlementsaccelerator.config.ObjectTypeConfig
import org.identityconnectors.framework.common.exceptions.ConnectorException
import org.identityconnectors.framework.common.objects.ObjectClass

/** Maps an ICF object class to its configured type; unknown classes fail fast. */
class ObjectClassResolver {

    /**
     * Throws ConnectorException naming the object class and the configured ones. The generic {@code __ACCOUNT__}
     * class is not mapped implicitly (Option B: one class per configured type).
     */
    static ObjectTypeConfig resolve(ObjectClass objectClass, ConnectorConfig config) {
        if (objectClass == null) throw new ConnectorException('No object class was given')
        ObjectTypeConfig type = config?.typeFor(objectClass.objectClassValue)
        if (type == null) {
            String configured = config?.objectTypes?.keySet()?.join(', ') ?: 'none'
            throw new ConnectorException("Object class '${objectClass.objectClassValue}' is not configured; the configured object classes are: ${configured}")
        }
        type
    }
}
