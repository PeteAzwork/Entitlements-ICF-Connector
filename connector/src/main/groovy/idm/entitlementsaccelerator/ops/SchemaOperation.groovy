package idm.entitlementsaccelerator.ops

import idm.entitlementsaccelerator.schema.SchemaFactory
import org.identityconnectors.framework.common.objects.Schema

/** Schema (design §9.5): delegates to SchemaFactory. */
class SchemaOperation {
    private final OperationContext ctx

    SchemaOperation(OperationContext ctx) { this.ctx = ctx }

    /** Builds the schema from configuration alone; makes no HTTP calls. */
    Schema execute(Class<?> connectorClass) {
        SchemaFactory.build(ctx.config, connectorClass)
    }
}
