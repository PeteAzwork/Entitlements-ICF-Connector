package idm.entitlementsaccelerator.ops

import idm.entitlementsaccelerator.config.ObjectTypeConfig
import idm.entitlementsaccelerator.http.HttpErrorMapper
import idm.entitlementsaccelerator.http.HttpResponse
import idm.entitlementsaccelerator.http.OpContext
import idm.entitlementsaccelerator.payload.EntityPayloadBuilder
import org.identityconnectors.framework.common.exceptions.InvalidAttributeValueException
import org.identityconnectors.framework.common.objects.Attribute
import org.identityconnectors.framework.common.objects.ObjectClass
import org.identityconnectors.framework.common.objects.OperationOptions
import org.identityconnectors.framework.common.objects.Uid

/**
 * Update (design §8.4, §9.2): PUT the total document built purely from the delivered attribute set.
 * Never reads the remote entity first. Refuses (InvalidAttributeValueException) an update that omits
 * {@code entityRelationships} or {@code directArchetypes}, because a PUT without them would delete edges.
 * A Uid containing {@code /} is rejected before any request, because the service cannot address it by path (P23).
 */
class UpdateOperation {
    private final OperationContext ctx

    UpdateOperation(OperationContext ctx) { this.ctx = ctx }

    Uid execute(ObjectClass objectClass, Uid uid, Set<Attribute> attributes, OperationOptions options) {
        ObjectTypeConfig type = ObjectClassResolver.resolve(objectClass, ctx.config)
        if (uid?.uidValue == null) throw new InvalidAttributeValueException("${type.name}: an update needs a Uid")
        Set<String> names = (attributes ?: [] as Set)*.name as Set
        [EntityPayloadBuilder.ATTR_ENTITY_RELATIONSHIPS, EntityPayloadBuilder.ATTR_DIRECT_ARCHETYPES].each { String required ->
            if (!(required in names)) {
                throw new InvalidAttributeValueException("${type.name} '${uid.uidValue}': the update omits ${required}. The service replaces it in full on every update, so a PUT without it would delete it (P4, P15); map it, empty if there are none")
            }
        }
        String id = uid.uidValue
        Map<String, Object> wire = EntityPayloadBuilder.build(id, type, attributes).toWire()

        HttpResponse response = Transport.call { ctx.api.replace(id, wire) }
        if (response.status == 400) Logs.error(ctx.log, "update of ${type.name} '${id}' refused: ${HttpErrorMapper.message(response, OpContext.UPDATE, ctx.config)}")
        HttpErrorMapper.raise(response, OpContext.UPDATE, ctx.config)
        Logs.ok(ctx.log, "updated ${type.name} '${id}'")
        uid
    }
}
