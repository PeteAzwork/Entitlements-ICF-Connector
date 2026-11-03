package au.com.azwork.idm.graphentity.config

/**
 * Parses the {@code objectTypes} document (design §6) into {@link ObjectTypeConfig} instances.
 * Pure function of its input; all validation errors are reported as
 * {@link org.identityconnectors.framework.common.exceptions.ConfigurationException} with the offending
 * type/property named in the message.
 */
class ObjectTypesParser {

    /** Parses from the JSON-encoded string form. TODO WS1. */
    static Map<String, ObjectTypeConfig> parse(String json) {
        throw new UnsupportedOperationException('TODO WS1: ObjectTypesParser.parse(String)')
    }

    /** Parses from an already-decoded map. TODO WS1. */
    static Map<String, ObjectTypeConfig> parse(Map<String, Object> decoded) {
        throw new UnsupportedOperationException('TODO WS1: ObjectTypesParser.parse(Map)')
    }
}
