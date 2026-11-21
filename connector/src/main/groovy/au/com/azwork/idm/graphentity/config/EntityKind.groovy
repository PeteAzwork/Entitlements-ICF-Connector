package au.com.azwork.idm.graphentity.config

/**
 * Entity kind per design §3 A4 / §6, with the numbering of decision P18: {@code subject} → base type 4,
 * {@code resource} → base type 2. The service's search results report the base type by name
 * ({@code "Subject"}, {@code "Resource"}), so reads accept either form.
 *
 * <p>P18 contradicts the service's own create samples (2 = Subject). Probe task WS0.15 confirms it against
 * the service before anything is created on a shared instance.
 */
enum EntityKind {
    SUBJECT(4, 'Subject'),
    RESOURCE(2, 'Resource')

    final int baseType
    final String serviceName

    EntityKind(int baseType, String serviceName) {
        this.baseType = baseType
        this.serviceName = serviceName
    }

    /** Parses the configuration string (case-insensitive). TODO WS1: throw ConfigurationException on unknown values. */
    static EntityKind fromConfig(String value) {
        throw new UnsupportedOperationException('TODO WS1: EntityKind.fromConfig')
    }

    /**
     * Reverse lookup used when decoding a remote document: a number (4, 2), a numeric string, or a name
     * ({@code "Subject"}, {@code "Resource"}, any case). Anything else, including the enum's other values 0, 1
     * and 3, decodes to null so a strange remote document never fails a read. TODO WS1.
     */
    static EntityKind fromBaseType(Object baseType) {
        throw new UnsupportedOperationException('TODO WS1: EntityKind.fromBaseType')
    }
}
