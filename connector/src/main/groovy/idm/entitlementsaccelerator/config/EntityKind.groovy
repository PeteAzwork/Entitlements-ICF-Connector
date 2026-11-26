package idm.entitlementsaccelerator.config

import org.identityconnectors.framework.common.exceptions.ConfigurationException

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

    /** Parses the configuration string, trimmed and case-insensitive. Anything else is a ConfigurationException. */
    static EntityKind fromConfig(String value) {
        String v = value?.trim()?.toLowerCase()
        EntityKind kind = values().find { it.name().toLowerCase() == v }
        if (kind == null) throw new ConfigurationException("entityKind '${value}' is not valid: use 'subject' or 'resource'")
        kind
    }

    /**
     * Reverse lookup used when decoding a remote document: a number (4, 2), a numeric string, or a name
     * ({@code "Subject"}, {@code "Resource"}, any case). Anything else, including the enum's other values 0, 1
     * and 3, decodes to null so a strange remote document never fails a read.
     */
    static EntityKind fromBaseType(Object baseType) {
        if (baseType instanceof Number) {
            Number n = baseType as Number
            return n.doubleValue() == n.intValue() ? values().find { it.baseType == n.intValue() } : null
        }
        if (!(baseType instanceof CharSequence)) return null
        String s = baseType.toString().trim()
        if (s ==~ /\d+/) return values().find { it.baseType == Integer.parseInt(s) }
        values().find { it.serviceName.equalsIgnoreCase(s) }
    }
}
