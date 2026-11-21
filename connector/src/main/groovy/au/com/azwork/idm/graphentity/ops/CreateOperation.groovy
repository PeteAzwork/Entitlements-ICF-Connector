package au.com.azwork.idm.graphentity.ops

import au.com.azwork.idm.graphentity.config.ObjectTypeConfig
import au.com.azwork.idm.graphentity.http.HttpErrorMapper
import au.com.azwork.idm.graphentity.http.HttpResponse
import au.com.azwork.idm.graphentity.http.OpContext
import au.com.azwork.idm.graphentity.config.ConnectorConfig
import au.com.azwork.idm.graphentity.model.Edge
import au.com.azwork.idm.graphentity.model.EntityDocument
import au.com.azwork.idm.graphentity.payload.EdgeVerifier
import au.com.azwork.idm.graphentity.payload.EntityPayloadBuilder
import org.identityconnectors.framework.common.objects.Attribute
import org.identityconnectors.framework.common.objects.ObjectClass
import org.identityconnectors.framework.common.objects.OperationOptions
import org.identityconnectors.framework.common.objects.Uid

/**
 * Create (design §9.1): POST the total document, then verification GET; log every dropped edge at WARN;
 * optionally re-PUT when {@code rePutOnDroppedEdges} is enabled. Returns the Uid (== remote ID).
 *
 * <p>Verification needs relationships, which only GET returns, so it is skipped when {@code byIdLookup} is
 * {@code search}. A verification that cannot run (an error status, as GET gives while C1 stands, or a transport
 * failure) is logged at WARN and the create still succeeds (P11). Logs above OK carry IDs, edge shapes, statuses
 * and redacted bodies, never attribute values (§13).
 */
class CreateOperation {
    private final OperationContext ctx

    CreateOperation(OperationContext ctx) { this.ctx = ctx }

    Uid execute(ObjectClass objectClass, Set<Attribute> attributes, OperationOptions options) {
        ObjectTypeConfig type = ObjectClassResolver.resolve(objectClass, ctx.config)
        String id = EntityPayloadBuilder.idForCreate(attributes)
        EntityDocument doc = EntityPayloadBuilder.build(id, type, attributes)
        Map<String, Object> wire = doc.toWire()

        HttpResponse response = Transport.call { ctx.api.create(wire) }
        if (response.status == 400) Logs.error(ctx.log, "create of ${type.name} '${id}' refused: ${HttpErrorMapper.message(response, OpContext.CREATE, ctx.config)}")
        HttpErrorMapper.raise(response, OpContext.CREATE, ctx.config)
        Logs.ok(ctx.log, "created ${type.name} '${id}'")

        if (ctx.config.byIdLookup == ConnectorConfig.ByIdLookup.GET && doc.relationships) {
            List<Edge> dropped = verify(type, id, doc.relationships)
            dropped.each { Logs.warn(ctx.log, EdgeVerifier.warnMessage(it)) }
            if (dropped && ctx.config.rePutOnDroppedEdges) rePut(type, id, wire)
        }
        new Uid(id)
    }

    /** The submitted edges the verification GET does not show; empty when verification could not run. */
    private List<Edge> verify(ObjectTypeConfig type, String id, List<Edge> submitted) {
        HttpResponse response
        try {
            response = ctx.api.get(id)
        } catch (IOException e) {
            Logs.warn(ctx.log, "verification GET failed for ${id}: ${e.class.simpleName}; relationships not checked (P11)")
            return []
        }
        if (!response.success || !(response.body instanceof Map)) {
            Logs.warn(ctx.log, "verification GET failed for ${id}: HTTP ${response.status}; relationships not checked (P11, C1)")
            return []
        }
        try {
            EdgeVerifier.dropped(submitted, EntityDocument.fromEntity(response.body as Map<String, Object>).relationships)
        } catch (IllegalArgumentException e) {
            Logs.warn(ctx.log, "verification GET for ${id} returned a document the connector cannot read; relationships not checked")
            []
        }
    }

    /** Aggressive mode (§9.1): restate the same document once. A failure here does not undo the create. */
    private void rePut(ObjectTypeConfig type, String id, Map<String, Object> wire) {
        try {
            HttpResponse response = ctx.api.replace(id, wire)
            if (!response.success) Logs.warn(ctx.log, "re-PUT of ${type.name} '${id}' after dropped edges failed: HTTP ${response.status}")
        } catch (IOException e) {
            Logs.warn(ctx.log, "re-PUT of ${type.name} '${id}' after dropped edges failed: ${e.class.simpleName}")
        }
    }
}
