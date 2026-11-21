package au.com.azwork.idm.graphentity.config

import groovy.json.JsonException
import groovy.json.JsonSlurper
import org.identityconnectors.framework.common.exceptions.ConfigurationException

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
 *
 * <p>Unknown keys are rejected rather than ignored, so a misspelt key (say {@code archetype}) cannot silently
 * drop part of the configuration. The relationship convention (§7) is not applied here: it needs the whole
 * document and runs in {@link au.com.azwork.idm.graphentity.convention.RelationshipConvention#validate}.
 */
class ObjectTypesParser {

    /** Attribute names the connector manages itself, or that collide with the ID (A3, C8). */
    static final Set<String> RESERVED_ATTRIBUTES =
        ['directArchetypes', 'entityRelationships', 'lastEvaluated', '__UID__', '__NAME__', 'ID', 'id'] as Set

    static final Set<String> TYPE_KEYS =
        ['idmResource', 'entityKind', 'idProperty', 'attributes', 'archetypes', 'archetypeProperty', 'relationships'] as Set
    static final Set<String> RELATIONSHIP_KEYS =
        ['remoteType', 'direction', 'sync', 'relatedType', 'reverseProperty'] as Set

    /** Parses from the JSON-encoded string form. */
    static Map<String, ObjectTypeConfig> parse(String json) {
        if (json == null || !json.trim()) throw new ConfigurationException('objectTypes is empty: it must be a JSON object with one entry per object type')
        Object decoded
        try {
            decoded = new JsonSlurper().parseText(json)
        } catch (JsonException | IllegalArgumentException e) {
            throw new ConfigurationException("objectTypes is not valid JSON: ${e.message}", e)
        }
        if (!(decoded instanceof Map)) throw new ConfigurationException('objectTypes must be a JSON object with one entry per object type')
        parse(decoded as Map<String, Object>)
    }

    /** Parses from an already-decoded map. */
    static Map<String, ObjectTypeConfig> parse(Map<String, Object> decoded) {
        if (decoded == null || decoded.isEmpty()) {
            throw new ConfigurationException('objectTypes defines no object types, so the connector would expose no object classes')
        }
        Map<String, ObjectTypeConfig> types = new LinkedHashMap<>()
        decoded.each { key, value ->
            String name = key as String
            if (!name?.trim()) throw new ConfigurationException('objectTypes has an entry with a blank type name')
            if (!(value instanceof Map)) throw new ConfigurationException("objectTypes.${name} must be an object")
            types[name] = parseType(name, value as Map)
        }
        checkScopeArchetypes(types)
        types
    }

    private static ObjectTypeConfig parseType(String name, Map raw) {
        String at = "objectTypes.${name}"
        unknownKeys(raw, TYPE_KEYS, at)
        String idmResource = requiredString(raw, 'idmResource', at)
        String idProperty = requiredString(raw, 'idProperty', at)
        String kind = requiredString(raw, 'entityKind', at)
        EntityKind entityKind
        try {
            entityKind = EntityKind.fromConfig(kind)
        } catch (ConfigurationException e) {
            throw new ConfigurationException("${at}.entityKind: ${e.message}")
        }
        List<String> attributes = stringList(raw, 'attributes', at)
        List<String> reserved = attributes.findAll { it in RESERVED_ATTRIBUTES }
        if (reserved) {
            throw new ConfigurationException("${at}.attributes: ${reserved.join(', ')} ${reserved.size() == 1 ? 'is' : 'are'} reserved for the connector and cannot be a domain attribute")
        }
        List<String> duplicates = attributes.findAll { a -> attributes.count(a) > 1 }.unique()
        if (duplicates) throw new ConfigurationException("${at}.attributes lists ${duplicates.join(', ')} more than once")
        List<String> archetypes = stringList(raw, 'archetypes', at)
        String archetypeProperty = optionalString(raw, 'archetypeProperty', at)
        new ObjectTypeConfig(
            name: name, idmResource: idmResource, entityKind: entityKind, idProperty: idProperty,
            attributes: attributes, archetypes: archetypes, archetypeProperty: archetypeProperty,
            relationships: parseRelationships(raw.relationships, at))
    }

    private static Map<String, RelationshipConfig> parseRelationships(Object raw, String at) {
        Map<String, RelationshipConfig> result = new LinkedHashMap<>()
        if (raw == null) return result
        if (!(raw instanceof Map)) throw new ConfigurationException("${at}.relationships must be an object keyed by relationship property")
        (raw as Map).each { key, value ->
            String property = key as String
            String rat = "${at}.relationships.${property}"
            if (!property?.trim()) throw new ConfigurationException("${at}.relationships has an entry with a blank property name")
            if (!(value instanceof Map)) throw new ConfigurationException("${rat} must be an object")
            Map entry = value as Map
            unknownKeys(entry, RELATIONSHIP_KEYS, rat)
            String remoteType = optionalString(entry, 'remoteType', rat)
            String directionText = optionalString(entry, 'direction', rat)
            if ((remoteType == null) != (directionText == null)) {
                throw new ConfigurationException("${rat} declares ${remoteType ? 'remoteType' : 'direction'} without ${remoteType ? 'direction' : 'remoteType'}: an override needs both, or neither to use the convention")
            }
            Direction direction = null
            if (directionText != null) {
                try {
                    direction = Direction.fromConfig(directionText)
                } catch (ConfigurationException e) {
                    throw new ConfigurationException("${rat}.direction: ${e.message}")
                }
            }
            result[property] = new RelationshipConfig(
                property: property, remoteType: remoteType, direction: direction, sync: syncFlag(entry, rat),
                relatedType: optionalString(entry, 'relatedType', rat), reverseProperty: optionalString(entry, 'reverseProperty', rat),
                explicit: remoteType != null)
        }
        result
    }

    /**
     * P19: within an entityKind shared by several types, every type needs a scope archetype (its first static
     * archetype) and no two may share one.
     */
    private static void checkScopeArchetypes(Map<String, ObjectTypeConfig> types) {
        types.values().groupBy { it.entityKind }.each { EntityKind kind, List<ObjectTypeConfig> group ->
            if (group.size() < 2) return
            List<String> names = group*.name
            group.findAll { !it.archetypes }.each { t ->
                throw new ConfigurationException("objectTypes.${t.name} shares entityKind '${kind.name().toLowerCase()}' with ${(names - t.name).join(', ')}, so it needs a scope archetype: its first entry in archetypes (P19)")
            }
            group.groupBy { it.archetypes[0] }.each { String scope, List<ObjectTypeConfig> sharing ->
                if (sharing.size() > 1) {
                    throw new ConfigurationException("objectTypes ${sharing*.name.join(' and ')} share entityKind '${kind.name().toLowerCase()}' and the scope archetype '${scope}': each needs its own first archetype, or their entities cannot be told apart (P19)")
                }
            }
        }
    }

    private static void unknownKeys(Map raw, Set<String> allowed, String at) {
        List<String> unknown = raw.keySet().collect { it as String }.findAll { !(it in allowed) }
        if (unknown) throw new ConfigurationException("${at} has unknown key${unknown.size() == 1 ? '' : 's'} ${unknown.join(', ')}; allowed: ${allowed.join(', ')}")
    }

    private static String requiredString(Map raw, String field, String at) {
        Object v = raw[field]
        if (v == null) throw new ConfigurationException("${at}.${field} is required")
        if (!(v instanceof CharSequence) || !v.toString().trim()) throw new ConfigurationException("${at}.${field} must be a non-blank string")
        v.toString().trim()
    }

    private static String optionalString(Map raw, String field, String at) {
        Object v = raw[field]
        if (v == null) return null
        if (!(v instanceof CharSequence) || !v.toString().trim()) throw new ConfigurationException("${at}.${field} must be a non-blank string when set")
        v.toString().trim()
    }

    private static List<String> stringList(Map raw, String field, String at) {
        Object v = raw[field]
        if (v == null) return new ArrayList<String>()
        if (!(v instanceof List)) throw new ConfigurationException("${at}.${field} must be a list of names")
        List<String> out = new ArrayList<>()
        (v as List).eachWithIndex { item, int i ->
            if (!(item instanceof CharSequence) || !item.toString().trim()) throw new ConfigurationException("${at}.${field}[${i}] must be a non-blank string")
            out << item.toString().trim()
        }
        out
    }

    private static boolean syncFlag(Map entry, String at) {
        Object v = entry.sync
        if (v == null) return true
        if (v instanceof Boolean) return v as boolean
        String s = v.toString().trim().toLowerCase()
        if (s == 'true') return true
        if (s == 'false') return false
        throw new ConfigurationException("${at}.sync must be true or false")
    }
}
