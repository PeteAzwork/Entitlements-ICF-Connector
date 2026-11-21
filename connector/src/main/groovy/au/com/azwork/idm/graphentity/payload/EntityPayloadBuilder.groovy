package au.com.azwork.idm.graphentity.payload

import au.com.azwork.idm.graphentity.config.ObjectTypeConfig
import au.com.azwork.idm.graphentity.model.EntityDocument
import org.identityconnectors.framework.common.objects.Attribute

/**
 * Builds the total write-model document from the ICF attribute set (design §8.3, §8.4; decisions P15, P17–P20):
 * <ul>
 *   <li>{@code id} from the uid or name (A3). An id containing {@code /} is rejected: the service stores it but
 *       cannot address it by path, so it could never be updated or deleted (decision P23, Q09.1);</li>
 *   <li>{@code baseType} from the type's entityKind (P18);</li>
 *   <li>{@code attributes}: every configured attribute that has a value, as a string (P20). Numbers and booleans
 *       become their text; a multi-valued attribute is rejected; an empty one is omitted. {@code ID} is not a
 *       domain attribute: {@link EntityDocument#toWire} adds it (C8);</li>
 *   <li>{@code directArchetypes}: the type's static archetypes first (the first is the scope archetype, P19), then
 *       delivered ones, without duplicates;</li>
 *   <li>{@code relationships}: parsed from {@code entityRelationships} (P17). Every edge must name the entity as
 *       source or target.</li>
 * </ul>
 * The payload is a pure function of its inputs (§8.1): no remote read is ever performed here.
 */
class EntityPayloadBuilder {

    static final String ATTR_DIRECT_ARCHETYPES = 'directArchetypes'
    static final String ATTR_ENTITY_RELATIONSHIPS = 'entityRelationships'
    /** Read-only; ignored if delivered back on a write (e.g. after a decode-then-build round trip). */
    static final String ATTR_LAST_EVALUATED = 'lastEvaluated'

    /**
     * @param id the remote ID (from {@code Uid} on update, from {@code __NAME__}/{@code __UID__} on create)
     * @param type the resolved object type configuration
     * @param attributes the ICF attributes delivered by the sync engine
     * TODO WS2. Throws InvalidAttributeValueException for attributes not in the configured list, a multi-valued
     * domain attribute, a malformed edge, an edge that does not involve the entity, or a blank id or one containing
     * {@code /} (P23).
     */
    static EntityDocument build(String id, ObjectTypeConfig type, Set<Attribute> attributes) {
        throw new UnsupportedOperationException('TODO WS2: EntityPayloadBuilder.build')
    }

    /** Resolves the remote ID for a create from __NAME__ (preferred) or __UID__; rejects one containing {@code /} (P23). TODO WS2. */
    static String idForCreate(Set<Attribute> attributes) {
        throw new UnsupportedOperationException('TODO WS2: EntityPayloadBuilder.idForCreate')
    }
}
