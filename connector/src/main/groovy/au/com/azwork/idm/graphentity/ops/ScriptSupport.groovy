package au.com.azwork.idm.graphentity.ops

import au.com.azwork.idm.graphentity.config.ConnectorConfig
import au.com.azwork.idm.graphentity.http.RestClientEntityApi
import org.identityconnectors.common.logging.Log

/**
 * Bridge between the Scripted REST harness bindings and the library. Duck-typed on purpose: the
 * {@code configuration} binding is a ScriptedRESTConfiguration in IDM and a simple stand-in in tests.
 *
 * Expected properties on {@code configuration}: {@code serviceAddress} (URI or String), {@code username},
 * {@code password} (GuardedString, String or char[]), and {@code propertyBag} (Map populated from
 * {@code customConfiguration}: objectTypes, timestampSearchParam, pageSizeDefault, deleteMissingTolerated).
 */
class ScriptSupport {

    /** TODO WS3. */
    static OperationContext contextFrom(Object configuration, Object connection, Log log) {
        throw new UnsupportedOperationException('TODO WS3: ScriptSupport.contextFrom')
    }

    /** Flattens the harness configuration into the map accepted by {@link ConnectorConfig#from}. TODO WS3. */
    static Map<String, Object> propertiesFrom(Object configuration) {
        throw new UnsupportedOperationException('TODO WS3: ScriptSupport.propertiesFrom')
    }
}
