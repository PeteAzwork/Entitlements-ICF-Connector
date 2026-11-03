package au.com.azwork.idm.graphentity.search

import au.com.azwork.idm.graphentity.config.ObjectTypeConfig
import au.com.azwork.idm.graphentity.model.EntityDocument

/**
 * Filters enumeration results to the requesting object class (design §9.4): an entity belongs to a type
 * when its {@code directArchetypes} contain every static archetype configured for the type and its
 * {@code BaseType} matches the type's entityKind. An optional {@code idPattern} (regex) on the type is the
 * §15 fallback for when archetypes are ambiguous.
 */
class TypeScoper {

    /** TODO WS4. */
    static boolean matches(EntityDocument doc, ObjectTypeConfig type) {
        throw new UnsupportedOperationException('TODO WS4: TypeScoper.matches')
    }
}
