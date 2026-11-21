package au.com.azwork.idm.graphentity.payload

import au.com.azwork.idm.graphentity.config.ObjectTypeConfig
import au.com.azwork.idm.graphentity.model.EntityDocument
import org.identityconnectors.framework.common.objects.ConnectorObject
import org.identityconnectors.framework.common.objects.ObjectClass

/**
 * Decomposes a canonical document (from the read model or a search item) into an ICF {@link ConnectorObject}
 * (design §9.4): __UID__ and __NAME__ from the ID; configured attributes the service returned, as strings
 * (unknown remote attributes are ignored); {@code directArchetypes} when the model carried them;
 * {@code entityRelationships} (re-serialised via EdgeCodec) only when the model carried relationships, which
 * search items never do (D4, deferred to WS10); and the read-only {@code lastEvaluated}.
 */
class EntityDecoder {

    /** TODO WS2. */
    static ConnectorObject decode(EntityDocument doc, ObjectTypeConfig type, ObjectClass objectClass) {
        throw new UnsupportedOperationException('TODO WS2: EntityDecoder.decode')
    }
}
