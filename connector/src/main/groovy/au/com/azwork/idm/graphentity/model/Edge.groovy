package au.com.azwork.idm.entitlementsaccelerator.model

import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString

/**
 * One relationship, with both ends named explicitly (decision P17). This is the service's write shape,
 * {@code {"sourceID", "targetID", "type"}}, and the value format of the {@code entityRelationships} ICF attribute.
 * The read model's {@code {source, target, type, sourceType}} is converted to the same shape.
 */
@EqualsAndHashCode
@ToString(includeNames = true)
class Edge {
    String sourceId
    String targetId
    String type

    static Edge of(String sourceId, String targetId, String type) {
        new Edge(sourceId: sourceId, targetId: targetId, type: type)
    }

    /** True when {@code entityId} is this edge's source or target. */
    boolean involves(String entityId) {
        entityId != null && (sourceId == entityId || targetId == entityId)
    }

    /** The end that is not {@code entityId}; for a self-edge, {@code entityId} itself. */
    String otherEnd(String entityId) {
        if (sourceId == entityId) return targetId
        if (targetId == entityId) return sourceId
        throw new IllegalArgumentException("Relationship ${sourceId} -${type}-> ${targetId} does not involve '${entityId}'")
    }

    /** Write shape, keys in this order: {@code sourceID}, {@code targetID}, {@code type}. */
    Map<String, Object> toWire() {
        Map<String, Object> m = new LinkedHashMap<>()
        m.sourceID = sourceId
        m.targetID = targetId
        m.type = type
        m
    }

    static final Set<String> WIRE_KEYS = ['sourceID', 'targetID', 'type'] as Set

    /**
     * Parses the write shape. Rejects (IllegalArgumentException) a missing, blank or non-string
     * {@code sourceID}, {@code targetID} or {@code type}, and any other key, including the design's old
     * one-sided {@code TargetID}/{@code SourceID} form.
     */
    static Edge fromWire(Map<String, Object> wire) {
        if (wire == null) throw new IllegalArgumentException('A relationship must be an object with sourceID, targetID and type')
        List<String> unknown = wire.keySet().collect { it as String }.findAll { !(it in WIRE_KEYS) }
        if (unknown) {
            throw new IllegalArgumentException("A relationship has unknown key${unknown.size() == 1 ? '' : 's'} ${unknown.join(', ')}; it must have exactly sourceID, targetID and type")
        }
        of(required(wire, 'sourceID'), required(wire, 'targetID'), required(wire, 'type'))
    }

    /** Parses the read model's {@code {source, target, type, sourceType}}; {@code sourceType} is dropped. */
    static Edge fromRead(Map<String, Object> read) {
        if (read == null) throw new IllegalArgumentException('A relationship must be an object with source, target and type')
        of(required(read, 'source'), required(read, 'target'), required(read, 'type'))
    }

    private static String required(Map<String, Object> m, String key) {
        Object v = m[key]
        if (!(v instanceof CharSequence) || !v.toString().trim()) {
            throw new IllegalArgumentException("A relationship's ${key} must be a non-blank string${v == null ? ', but it is missing' : ''}")
        }
        v.toString()
    }
}
