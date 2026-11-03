package au.com.azwork.idm.graphentity.payload

import au.com.azwork.idm.graphentity.config.ObjectTypeConfig
import au.com.azwork.idm.graphentity.model.EntityDocument
import org.identityconnectors.framework.common.objects.Attribute

/**
 * Builds the total entity document from the ICF attribute set (design §8.3, §8.4):
 * {@code ID} from the uid/name, {@code BaseType} from entityKind, {@code attributes} from the configured
 * list plus the mirrored {@code ID}, {@code directArchetypes} from the attribute (merged with the static
 * archetypes of the type), {@code Relationships} from parsing {@code entityRelationships}.
 *
 * The payload is a pure function of its inputs (§8.1): no remote read is ever performed here.
 */
class EntityPayloadBuilder {

    static final String ATTR_DIRECT_ARCHETYPES = 'directArchetypes'
    static final String ATTR_ENTITY_RELATIONSHIPS = 'entityRelationships'
    static final String ATTR_LAST_UPDATED = 'lastUpdated'

    /**
     * @param id the remote ID (from {@code Uid} on update, from {@code __NAME__}/{@code __UID__} on create)
     * @param type the resolved object type configuration
     * @param attributes the ICF attributes delivered by the sync engine
     * TODO WS2. Throws InvalidAttributeValueException for attributes not in the configured list, for a
     * malformed edge, or when the id is blank.
     */
    static EntityDocument build(String id, ObjectTypeConfig type, Set<Attribute> attributes) {
        throw new UnsupportedOperationException('TODO WS2: EntityPayloadBuilder.build')
    }

    /** Resolves the remote ID for a create from __NAME__ (preferred) or __UID__. TODO WS2. */
    static String idForCreate(Set<Attribute> attributes) {
        throw new UnsupportedOperationException('TODO WS2: EntityPayloadBuilder.idForCreate')
    }
}
