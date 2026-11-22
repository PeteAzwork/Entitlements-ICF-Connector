package au.com.azwork.idm.entitlementsaccelerator.model

import au.com.azwork.idm.entitlementsaccelerator.config.EntityKind
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString

/**
 * An entity in the connector's canonical form, translated to and from the service's three document models
 * (service contract §5.2):
 * <ul>
 *   <li><b>write model</b> (EntityDTO, create and update): {@code id}, numeric {@code baseType},
 *       {@code attributes} as string → string, which must include {@code ID} equal to {@code id} (by design, C8),
 *       {@code relationships} as {@code {sourceID, targetID, type}}, {@code directArchetypes};</li>
 *   <li><b>read model</b> (Entity, GET and DELETE): {@code id}, {@code baseType}, {@code Attributes} as
 *       name → {@code {name, type, value}}, {@code compoundAttributes}, {@code relationships} as
 *       {@code {source, target, type, sourceType}}, {@code lastEvaluated}, {@code reconciliationStarted}.
 *       It carries <i>no</i> archetypes;</li>
 *   <li><b>search item</b>: {@code uid}, {@code name}, {@code baseType} (as a name), {@code attributes} as
 *       name → value, {@code isArchetypes}, {@code directArchetypes}, {@code lastEvaluated}. It carries <i>no</i>
 *       relationships. By design, its {@code isArchetypes} lists the archetypes assigned through
 *       {@code directArchetypes} as well as those assigned by policy, and its own {@code directArchetypes} is empty
 *       (C9).</li>
 * </ul>
 * {@link #attributes} holds domain attributes only: {@code attributes.ID} is added on the way out and removed on
 * the way in.
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
    /**
     * Archetypes assigned through the write model. Null when the source model does not report them separately: the
     * read model carries none, and a search item reports them in {@link #isArchetypes} (C9).
     */
    List<String> directArchetypes = []
    /**
     * The archetypes a search item reports, read-only: those assigned through {@code directArchetypes} and those
     * assigned by policy (C9). Scoping matches the scope archetype here (P19, amended). Null for other models.
     */
    List<String> isArchetypes
    /** Policy evaluation time, read-only. Not a modification time. */
    String lastEvaluated

    /** The attribute the service requires to equal the id (C8). Never a domain attribute. */
    static final String ID_ATTRIBUTE = 'ID'

    /**
     * Write model. Always emits {@code relationships} and {@code directArchetypes}, even when empty, because
     * the service removes anything a write omits (P15). Adds {@code attributes.ID} equal to {@code id}, which the
     * service requires (C8). Never emits read-only fields.
     */
    Map<String, Object> toWire() {
        Map<String, String> wireAttributes = new LinkedHashMap<>()
        wireAttributes[ID_ATTRIBUTE] = id
        (attributes ?: [:]).each { k, v -> if (k != ID_ATTRIBUTE) wireAttributes[k] = v }
        Map<String, Object> m = new LinkedHashMap<>()
        m.id = id
        m.baseType = baseType
        m.attributes = wireAttributes
        m.relationships = (relationships ?: []).collect { it.toWire() }
        m.directArchetypes = new ArrayList<String>(directArchetypes ?: [])
        m
    }

    /** Parses a write-model document (a create or update body); {@code attributes.ID} is dropped. */
    static EntityDocument fromWire(Map<String, Object> wire) {
        if (wire == null) throw new IllegalArgumentException('An entity document must be an object')
        new EntityDocument(
            id: requiredId(wire.id, 'id'),
            baseType: baseTypeOf(wire.baseType),
            attributes: plainAttributes(wire.attributes),
            relationships: list(wire.relationships, 'relationships').collect { Edge.fromWire(it as Map<String, Object>) },
            directArchetypes: strings(wire.directArchetypes))
    }

    /** Parses the read model returned by GET and DELETE. Archetypes are unknown (null). */
    static EntityDocument fromEntity(Map<String, Object> entity) {
        if (entity == null) throw new IllegalArgumentException('An entity must be an object')
        Object attrs = entity.containsKey('Attributes') ? entity.Attributes : entity.attributes
        new EntityDocument(
            id: requiredId(entity.id, 'id'),
            baseType: baseTypeOf(entity.baseType),
            attributes: attributeObjects(attrs),
            relationships: list(entity.relationships, 'relationships').collect { Edge.fromRead(it as Map<String, Object>) },
            directArchetypes: null,
            lastEvaluated: entity.lastEvaluated?.toString())
    }

    /**
     * Parses one search result item. Relationships are unknown (null). {@code isArchetypes} is kept as reported;
     * {@code directArchetypes} is null unless the item reports some there (C9).
     */
    static EntityDocument fromSearchItem(Map<String, Object> item) {
        if (item == null) throw new IllegalArgumentException('A search result item must be an object')
        Object uid = item.uid
        String id = uid instanceof CharSequence && uid.toString().trim() ? uid.toString() : requiredId(item.name, 'uid or name')
        List<String> direct = strings(item.directArchetypes)
        new EntityDocument(
            id: id,
            baseType: baseTypeOf(item.baseType),
            attributes: plainAttributes(item.attributes),
            relationships: null,
            directArchetypes: direct ? direct : null,
            isArchetypes: item.isArchetypes == null ? null : strings(item.isArchetypes),
            lastEvaluated: item.lastEvaluated?.toString())
    }

    private static String requiredId(Object v, String what) {
        if (!(v instanceof CharSequence) || !v.toString().trim()) throw new IllegalArgumentException("The entity has no ${what}")
        v.toString()
    }

    /** A number is kept as is; a name is mapped through EntityKind (P18); anything else is unknown (null). */
    private static Integer baseTypeOf(Object raw) {
        if (raw instanceof Number) {
            Number n = raw as Number
            return n.doubleValue() == n.intValue() ? n.intValue() : null
        }
        EntityKind.fromBaseType(raw)?.baseType
    }

    /** Name → value attributes (write model, search item), as strings; {@code ID} and null values dropped (P20). */
    private static Map<String, String> plainAttributes(Object raw) {
        Map<String, String> out = new LinkedHashMap<>()
        if (raw == null) return out
        if (!(raw instanceof Map)) throw new IllegalArgumentException('attributes must be an object')
        (raw as Map).each { k, v ->
            String name = k as String
            if (name != ID_ATTRIBUTE && v != null) out[name] = v.toString()
        }
        out
    }

    /** The read model's name → {name, type, value} attributes, as strings; {@code ID} and null values dropped. */
    private static Map<String, String> attributeObjects(Object raw) {
        Map<String, String> out = new LinkedHashMap<>()
        if (raw == null) return out
        if (!(raw instanceof Map)) throw new IllegalArgumentException('Attributes must be an object')
        (raw as Map).each { k, v ->
            String name = k as String
            Object value = v instanceof Map ? (v as Map).value : v
            if (name != ID_ATTRIBUTE && value != null) out[name] = value.toString()
        }
        out
    }

    private static List list(Object raw, String what) {
        if (raw == null) return []
        if (!(raw instanceof Collection)) throw new IllegalArgumentException("${what} must be a list")
        new ArrayList(raw as Collection)
    }

    private static List<String> strings(Object raw) {
        list(raw, 'archetypes').findAll { it != null }.collect { it.toString() } as List<String>
    }
}
