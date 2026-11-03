package au.com.azwork.idm.graphentity.ops

import org.identityconnectors.framework.common.objects.Attribute
import org.identityconnectors.framework.common.objects.ObjectClass
import org.identityconnectors.framework.common.objects.OperationOptions
import org.identityconnectors.framework.common.objects.Uid

/**
 * Create (design §9.1): POST the total document, then verification GET; log every dropped edge at WARN;
 * optionally re-PUT when {@code rePutOnDroppedEdges} is enabled. Returns the Uid (== remote ID).
 */
class CreateOperation {
    private final OperationContext ctx

    CreateOperation(OperationContext ctx) { this.ctx = ctx }

    /** TODO WS3. */
    Uid execute(ObjectClass objectClass, Set<Attribute> attributes, OperationOptions options) {
        throw new UnsupportedOperationException('TODO WS3: CreateOperation.execute')
    }
}
