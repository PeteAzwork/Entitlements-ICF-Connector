package au.com.azwork.idm.graphentity.payload

import au.com.azwork.idm.graphentity.config.ObjectTypeConfig
import au.com.azwork.idm.graphentity.model.EntityDocument
import org.identityconnectors.framework.common.objects.ConnectorObject
import org.identityconnectors.framework.common.objects.ObjectClass

/**
 * Decomposes a remote document back into an ICF {@link ConnectorObject} (design §9.4): __UID__ and
 * __NAME__ from {@code ID}, configured attributes from the attribute map (unknown remote attributes are
 * ignored), {@code directArchetypes}, {@code entityRelationships} (re-serialised via EdgeCodec) and the
 * read-only {@code lastUpdated}.
 */
class EntityDecoder {

    /** TODO WS2. */
    static ConnectorObject decode(EntityDocument doc, ObjectTypeConfig type, ObjectClass objectClass) {
        throw new UnsupportedOperationException('TODO WS2: EntityDecoder.decode')
    }
}
