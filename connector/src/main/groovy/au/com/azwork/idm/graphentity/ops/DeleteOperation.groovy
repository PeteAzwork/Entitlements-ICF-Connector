package au.com.azwork.idm.graphentity.ops

import org.identityconnectors.framework.common.objects.ObjectClass
import org.identityconnectors.framework.common.objects.OperationOptions
import org.identityconnectors.framework.common.objects.Uid

/** Delete (design §9.3): DELETE /admin/entities/{id}; 404 tolerated per {@code deleteMissingTolerated}. */
class DeleteOperation {
    private final OperationContext ctx

    DeleteOperation(OperationContext ctx) { this.ctx = ctx }

    /** TODO WS3. */
    void execute(ObjectClass objectClass, Uid uid, OperationOptions options) {
        throw new UnsupportedOperationException('TODO WS3: DeleteOperation.execute')
    }
}
