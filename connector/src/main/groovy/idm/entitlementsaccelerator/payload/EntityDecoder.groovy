package idm.entitlementsaccelerator.payload

import idm.entitlementsaccelerator.codec.EdgeCodec
import idm.entitlementsaccelerator.config.ObjectTypeConfig
import idm.entitlementsaccelerator.model.EntityDocument
import org.identityconnectors.framework.common.objects.AttributeBuilder
import org.identityconnectors.framework.common.objects.ConnectorObject
import org.identityconnectors.framework.common.objects.ConnectorObjectBuilder
import org.identityconnectors.framework.common.objects.ObjectClass

/**
 * Decomposes a canonical document (from the read model or a search item) into an ICF {@link ConnectorObject}
 * (design §9.4): __UID__ and __NAME__ from the ID; configured attributes the service returned, as strings
 * (unknown remote attributes are ignored; so is {@code ID}, which only exists for C8; an attribute whose value is
 * {@code ""} is treated as absent, because that is how the connector clears one, P20 amended); {@code directArchetypes}
 * when the model carried archetypes: a search item's {@code isArchetypes}, which by design include the archetypes
 * assigned through {@code directArchetypes} (C9);
 * {@code entityRelationships} (re-serialised via EdgeCodec) only when the model carried relationships, which
 * search items never do (D4, deferred to WS10); and the read-only {@code lastEvaluated}.
 */
class EntityDecoder {

    static ConnectorObject decode(EntityDocument doc, ObjectTypeConfig type, ObjectClass objectClass) {
        if (doc?.id == null) throw new IllegalArgumentException('Cannot decode an entity without an id')
        ConnectorObjectBuilder b = new ConnectorObjectBuilder().setObjectClass(objectClass).setUid(doc.id).setName(doc.id)
        type.attributes.each { String name ->
            String value = doc.attributes?.get(name)
            if (value != null && value != '') b.addAttribute(AttributeBuilder.build(name, value))
        }
        List<String> archetypes = doc.isArchetypes != null ? doc.isArchetypes : doc.directArchetypes
        if (archetypes != null) {
            b.addAttribute(AttributeBuilder.build(EntityPayloadBuilder.ATTR_DIRECT_ARCHETYPES, new ArrayList<Object>(archetypes)))
        }
        if (doc.relationships != null) {
            b.addAttribute(AttributeBuilder.build(EntityPayloadBuilder.ATTR_ENTITY_RELATIONSHIPS, new ArrayList<Object>(EdgeCodec.serialiseAll(doc.relationships))))
        }
        if (doc.lastEvaluated != null) b.addAttribute(AttributeBuilder.build(EntityPayloadBuilder.ATTR_LAST_EVALUATED, doc.lastEvaluated))
        b.build()
    }
}
