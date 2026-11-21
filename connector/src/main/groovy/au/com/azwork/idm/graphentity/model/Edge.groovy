package au.com.azwork.idm.graphentity.model

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

    /** True when {@code entityId} is this edge's source or target. TODO WS2. */
    boolean involves(String entityId) {
        throw new UnsupportedOperationException('TODO WS2: Edge.involves')
    }

    /** The end that is not {@code entityId}; for a self-edge, {@code entityId} itself. TODO WS2. */
    String otherEnd(String entityId) {
        throw new UnsupportedOperationException('TODO WS2: Edge.otherEnd')
    }

    /** Write shape, keys in this order: {@code sourceID}, {@code targetID}, {@code type}. TODO WS2. */
    Map<String, Object> toWire() {
        throw new UnsupportedOperationException('TODO WS2: Edge.toWire')
    }

    /**
     * Parses the write shape. Rejects (IllegalArgumentException) a missing, blank or non-string
     * {@code sourceID}, {@code targetID} or {@code type}, and any other key, including the design's old
     * one-sided {@code TargetID}/{@code SourceID} form. TODO WS2.
     */
    static Edge fromWire(Map<String, Object> wire) {
        throw new UnsupportedOperationException('TODO WS2: Edge.fromWire')
    }

    /** Parses the read model's {@code {source, target, type, sourceType}}; {@code sourceType} is dropped. TODO WS2. */
    static Edge fromRead(Map<String, Object> read) {
        throw new UnsupportedOperationException('TODO WS2: Edge.fromRead')
    }
}
