package au.com.azwork.idm.graphentity.config

import groovy.transform.Canonical

/** One entry of the {@code objectTypes} configuration (design §6). */
@Canonical
class ObjectTypeConfig {
    /** ICF object class name, e.g. {@code user}. */
    String name
    /** IDM managed resource, e.g. {@code managed/user}. */
    String idmResource
    EntityKind entityKind
    /** IDM property whose value is the remote ID and ICF __UID__/__NAME__ (A3). */
    String idProperty
    List<String> attributes = []
    List<String> archetypes = []
    String archetypeProperty
    Map<String, RelationshipConfig> relationships = [:]

    /** Relationship entries that participate in sync ({@code sync != false}). TODO WS1. */
    Map<String, RelationshipConfig> syncedRelationships() {
        throw new UnsupportedOperationException('TODO WS1: ObjectTypeConfig.syncedRelationships')
    }
}
