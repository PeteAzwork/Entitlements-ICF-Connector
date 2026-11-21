package au.com.azwork.idm.graphentity.config

/** Edge direction from the perspective of the entity being written (design §2.2, §7). */
enum Direction {
    /** This entity is the relationship's source ({@code sourceID}, decision P17). */
    OUTBOUND,
    /** This entity is the relationship's target ({@code targetID}, decision P17). */
    INBOUND

    static Direction fromConfig(String value) {
        throw new UnsupportedOperationException('TODO WS1: Direction.fromConfig')
    }

    Direction opposite() {
        throw new UnsupportedOperationException('TODO WS1: Direction.opposite')
    }
}
