package au.com.azwork.idm.graphentity.model

import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString

/**
 * An entity in the connector's canonical form, translated to and from the service's three document models
 * (service contract §5.2):
 * <ul>
 *   <li><b>write model</b> (EntityDTO, create and update): {@code id}, numeric {@code baseType},
 *       {@code attributes} as string → string, {@code relationships} as {@code {sourceID, targetID, type}},
 *       {@code directArchetypes};</li>
 *   <li><b>read model</b> (Entity, GET and DELETE): {@code id}, {@code baseType}, {@code Attributes} as
 *       name → {@code {name, type, value}}, {@code compoundAttributes}, {@code relationships} as
 *       {@code {source, target, type, sourceType}}, {@code lastEvaluated}, {@code reconciliationStarted}.
 *       It carries <i>no</i> archetypes;</li>
 *   <li><b>search item</b>: {@code uid}, {@code name}, {@code baseType} (as a name), {@code attributes} as
 *       name → value, {@code isArchetypes}, {@code directArchetypes}, {@code lastEvaluated}. It carries <i>no</i>
 *       relationships.</li>
 * </ul>
 * A field that a model does not carry is {@code null} ("unknown"), which is different from empty ("none").
 */
@EqualsAndHashCode
@ToString(includeNames = true)
class EntityDocument {
    String id
    /** Numeric base type (decision P18: 4 = Subject, 2 = Resource). */
    Integer baseType
    /** Domain attributes, single-valued strings (decision P20). */
    Map<String, String> attributes = [:]
    /** Null when the source model carries no relationships (search items). */
    List<Edge> relationships = []
    /** Null when the source model carries no archetypes (the read model). */
    List<String> directArchetypes = []
    /** Policy-assigned archetypes, read-only, search items only. Never used for scoping (P19). */
    List<String> isArchetypes
    /** Policy evaluation time, read-only. Not a modification time. */
    String lastEvaluated

    /**
     * Write model. Always emits {@code relationships} and {@code directArchetypes}, even when empty, because
     * the service removes anything a write omits (P15). Never emits read-only fields. TODO WS2.
     */
    Map<String, Object> toWire() {
        throw new UnsupportedOperationException('TODO WS2: EntityDocument.toWire')
    }

    /** Parses a write-model document (a create or update body). TODO WS2. */
    static EntityDocument fromWire(Map<String, Object> wire) {
        throw new UnsupportedOperationException('TODO WS2: EntityDocument.fromWire')
    }

    /** Parses the read model returned by GET and DELETE. Archetypes are unknown (null). TODO WS2. */
    static EntityDocument fromEntity(Map<String, Object> entity) {
        throw new UnsupportedOperationException('TODO WS2: EntityDocument.fromEntity')
    }

    /** Parses one search result item. Relationships are unknown (null). TODO WS2. */
    static EntityDocument fromSearchItem(Map<String, Object> item) {
        throw new UnsupportedOperationException('TODO WS2: EntityDocument.fromSearchItem')
    }
}
