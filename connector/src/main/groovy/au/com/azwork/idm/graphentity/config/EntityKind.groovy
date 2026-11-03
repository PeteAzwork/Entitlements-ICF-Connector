package au.com.azwork.idm.graphentity.config

/**
 * Entity kind per design §3 A4 / §6: {@code subject} → BaseType 4, {@code resource} → BaseType 2.
 */
enum EntityKind {
    SUBJECT(4),
    RESOURCE(2)

    final int baseType

    EntityKind(int baseType) { this.baseType = baseType }

    /** Parses the configuration string (case-insensitive). TODO WS1: throw ConfigurationException on unknown values. */
    static EntityKind fromConfig(String value) {
        throw new UnsupportedOperationException('TODO WS1: EntityKind.fromConfig')
    }

    /** Reverse lookup used when decoding a remote document (§9.4). TODO WS1. */
    static EntityKind fromBaseType(Integer baseType) {
        throw new UnsupportedOperationException('TODO WS1: EntityKind.fromBaseType')
    }
}
