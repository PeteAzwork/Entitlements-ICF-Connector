package au.com.azwork.idm.graphentity.ops

import org.identityconnectors.framework.common.objects.Schema

/** Schema (design §9.5): delegates to SchemaFactory. */
class SchemaOperation {
    private final OperationContext ctx

    SchemaOperation(OperationContext ctx) { this.ctx = ctx }

    /** TODO WS1. */
    Schema execute(Class<?> connectorClass) {
        throw new UnsupportedOperationException('TODO WS1: SchemaOperation.execute')
    }
}
