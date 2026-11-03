package au.com.azwork.idm.graphentity.model

import au.com.azwork.idm.graphentity.config.Direction
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString

/**
 * A directed edge as seen from one entity (design §2.2). Exactly one of {@code targetId} / {@code sourceId}
 * is set: {@code targetId} for an outbound edge, {@code sourceId} for an inbound one.
 */
@EqualsAndHashCode
@ToString(includeNames = true)
class Edge {
    String targetId
    String sourceId
    String type

    static Edge outbound(String targetId, String type) {
        new Edge(targetId: targetId, type: type)
    }

    static Edge inbound(String sourceId, String type) {
        new Edge(sourceId: sourceId, type: type)
    }

    Direction getDirection() {
        throw new UnsupportedOperationException('TODO WS2: Edge.getDirection')
    }

    /** The other endpoint's ID irrespective of direction. */
    String getOtherId() {
        throw new UnsupportedOperationException('TODO WS2: Edge.getOtherId')
    }

    /** Wire form: {@code [TargetID: ..., type: ...]} or {@code [SourceID: ..., type: ...]}. TODO WS2. */
    Map<String, Object> toWire() {
        throw new UnsupportedOperationException('TODO WS2: Edge.toWire')
    }

    /** Parses a wire-form map; rejects maps with both or neither endpoint, or no type. TODO WS2. */
    static Edge fromWire(Map<String, Object> wire) {
        throw new UnsupportedOperationException('TODO WS2: Edge.fromWire')
    }

    /**
     * The same edge expressed from the other endpoint's perspective, given this entity's ID
     * (e.g. Sanne's {@code TargetID=acct/Owns} becomes the account's {@code SourceID=Sanne/Owns}). TODO WS2.
     */
    Edge flip(String thisEntityId) {
        throw new UnsupportedOperationException('TODO WS2: Edge.flip')
    }
}
