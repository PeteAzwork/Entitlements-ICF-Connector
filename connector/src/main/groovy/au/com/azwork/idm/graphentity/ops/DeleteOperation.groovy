package au.com.azwork.idm.entitlementsaccelerator.ops

import au.com.azwork.idm.entitlementsaccelerator.config.ObjectTypeConfig
import au.com.azwork.idm.entitlementsaccelerator.http.HttpErrorMapper
import au.com.azwork.idm.entitlementsaccelerator.http.HttpResponse
import au.com.azwork.idm.entitlementsaccelerator.http.OpContext
import au.com.azwork.idm.entitlementsaccelerator.payload.EntityPayloadBuilder
import org.identityconnectors.framework.common.objects.ObjectClass
import org.identityconnectors.framework.common.objects.OperationOptions
import org.identityconnectors.framework.common.objects.Uid

/**
 * Delete (design §9.3): DELETE /admin/entities/{id}; 404 tolerated per {@code deleteMissingTolerated}. A Uid
 * containing {@code /} is rejected before any request, because the service cannot address it by path (P23).
 */
class DeleteOperation {
    private final OperationContext ctx

    DeleteOperation(OperationContext ctx) { this.ctx = ctx }

    /** The response body (the deleted entity) is not used, so nothing of it is logged. */
    void execute(ObjectClass objectClass, Uid uid, OperationOptions options) {
        ObjectTypeConfig type = ObjectClassResolver.resolve(objectClass, ctx.config)
        String id = EntityPayloadBuilder.checkId(uid?.uidValue, 'Uid')
        HttpResponse response = Transport.call { ctx.api.delete(id) }
        if (HttpErrorMapper.raise(response, OpContext.DELETE, ctx.config)) {
            Logs.info(ctx.log, "delete of ${type.name} '${id}': already absent (HTTP 404), treated as deleted")
        } else {
            Logs.ok(ctx.log, "deleted ${type.name} '${id}'")
        }
    }
}
