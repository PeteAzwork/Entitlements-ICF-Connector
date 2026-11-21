package au.com.azwork.idm.graphentity.search

import au.com.azwork.idm.graphentity.config.ObjectTypeConfig
import au.com.azwork.idm.graphentity.model.EntityDocument

/**
 * Scopes service entities to an IDM object type (design §9.4; decision P19).
 *
 * <p>The search already restricts results to the type's base type through {@code objectClass}. Within a base
 * type, a type's entities are those that carry its <b>scope archetype</b>, the first entry of its static archetypes.
 * P19 as amended (2026-11-21): search reports archetypes assigned through {@code directArchetypes} in
 * {@code isArchetypes} (C9, by design), so the scope archetype is matched in {@code isArchetypes}, or in
 * {@code directArchetypes} when a document carries them there. Policy rules can also add to {@code isArchetypes};
 * choose scope archetypes that no policy assigns.
 */
class TypeScoper {

    /** The type's scope archetype, or null when it has no static archetypes. TODO WS4. */
    static String scopeArchetype(ObjectTypeConfig type) {
        throw new UnsupportedOperationException('TODO WS4: TypeScoper.scopeArchetype')
    }

    /**
     * Enumeration scope: the document's base type matches the type's kind and its {@code isArchetypes} or
     * {@code directArchetypes} contain the scope archetype. A type without a scope archetype matches on base type alone (allowed only when
     * no other type shares its kind). TODO WS4.
     */
    static boolean inEnumerationScope(EntityDocument doc, ObjectTypeConfig type) {
        throw new UnsupportedOperationException('TODO WS4: TypeScoper.inEnumerationScope')
    }

    /**
     * Read-by-ID scope: the base type alone. An existing entity without the scope archetype is still found, so
     * IDM can correlate it and the next update stamps the archetype. A document with an unknown base type is out of
     * scope. TODO WS4.
     */
    static boolean inByIdScope(EntityDocument doc, ObjectTypeConfig type) {
        throw new UnsupportedOperationException('TODO WS4: TypeScoper.inByIdScope')
    }
}
