package au.com.azwork.idm.graphentity.config

/**
 * Parses the {@code objectTypes} document (design §6) into {@link ObjectTypeConfig} instances.
 *
 * <p>Scope archetypes (decision P19): a type's first static archetype is its scope archetype. When two or
 * more types share an entityKind, each must have a scope archetype and no two may share one, or enumeration
 * could not tell their entities apart.
 *
 * <p>Reserved attribute names: {@code directArchetypes}, {@code entityRelationships}, {@code lastEvaluated},
 * {@code __UID__}, {@code __NAME__}, {@code ID} and {@code id}.
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
