package au.com.azwork.idm.graphentity.schema

import org.identityconnectors.common.logging.Log
import org.identityconnectors.framework.common.objects.AttributeInfo
import org.identityconnectors.framework.common.objects.ObjectClassInfo
import org.identityconnectors.framework.common.objects.Schema
import org.identityconnectors.framework.spi.operations.AuthenticateOp
import org.identityconnectors.framework.spi.operations.ResolveUsernameOp
import org.identityconnectors.framework.spi.operations.SyncOp

/**
 * Hands a {@link Schema} built by {@link SchemaFactory} to the Groovy toolkit's schema DSL (WS3.01). Ping's
 * schema-script reference: the script receives {@code builder}, an {@code ICFObjectBuilder}, calls
 * {@code builder.schema { objectClass { type ...; attribute ...; disable ... } }} and returns nothing.
 *
 * <p>The toolkit derives each class's operations from the connector class, which implements every operation, so
 * the operations the connector has no script for are disabled per class. The reference does not give
 * {@code disable}'s parameter type; if the builder refuses the SPI classes, that is logged at WARN and the class
 * is still defined (IDM never calls Sync or Authenticate without a mapping or script for them).
 */
class SchemaDsl {
    /** Operations the connector deliberately does not implement (§4). */
    static final List<Class> UNSUPPORTED = [SyncOp, AuthenticateOp, ResolveUsernameOp].asImmutable()

    static void define(Object builder, Schema schema, Log log) {
        builder.schema {
            schema.objectClassInfo.each { ObjectClassInfo info ->
                objectClass {
                    type info.type
                    info.attributeInfo.each { AttributeInfo a -> attribute a }
                    try {
                        disable(*UNSUPPORTED)
                    } catch (MissingMethodException | IllegalArgumentException e) {
                        log?.warn('{0}', "The schema builder did not accept the operations to disable for ${info.type}: ${e.class.simpleName}".toString())
                    }
                }
            }
        }
    }
}
