package au.com.azwork.idm.graphentity.config

/** Edge direction from the perspective of the entity being written (design §2.2, §7). */
enum Direction {
    /** This entity → target; wire field {@code TargetID}. */
    OUTBOUND,
    /** Source → this entity; wire field {@code SourceID}. */
    INBOUND

    static Direction fromConfig(String value) {
        throw new UnsupportedOperationException('TODO WS1: Direction.fromConfig')
    }

    Direction opposite() {
        throw new UnsupportedOperationException('TODO WS1: Direction.opposite')
    }
}
