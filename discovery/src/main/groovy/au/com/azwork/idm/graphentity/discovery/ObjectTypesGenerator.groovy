package au.com.azwork.idm.graphentity.discovery

import au.com.azwork.idm.graphentity.config.ObjectTypesParser
import au.com.azwork.idm.graphentity.convention.RelationshipConvention
import org.identityconnectors.framework.common.exceptions.ConfigurationException

/**
 * Generates the connector {@code objectTypes} block from managed types (design §12), applying the §7
 * convention to every relationship pair, flagging what the convention cannot resolve, and running the
 * same consistency validation the connector runs at load time.
 */
class ObjectTypesGenerator {

    /** Names that look like a user-facing unique key, tried when no natural-key policy marks one. */
    static final List<String> USERNAME_LIKE = ['userName', 'username', 'login', 'loginName', 'uid']

    static GenerationResult generate(List<ManagedType> types, GeneratorOptions options) {
        GenerationResult result = new GenerationResult()
        Map<String, ManagedType> byName = types.collectEntries { [(it.name): it] }
        Set<String> selected = new LinkedHashSet<>(options.selectedTypes ?: [])
        if (!selected) result.errors << 'No types were selected: name at least one managed type to generate for'

        selected.each { String name ->
            ManagedType type = byName[name]
            if (type == null) {
                result.errors << "Selected type '${name}' is not in managed.json; its types are: ${byName.keySet().join(', ')}".toString()
                return
            }
            Map block = typeBlock(type, selected, options, result)
            if (block != null) result.objectTypes[name] = block
        }

        if (result.objectTypes) {
            try {
                result.warnings.addAll(RelationshipConvention.validate(ObjectTypesParser.parse(result.objectTypes as Map<String, Object>)))
            } catch (ConfigurationException e) {
                result.errors << "The generated configuration fails the connector's validation: ${e.message}".toString()
            }
        }
        result
    }

    /** One type's block, or null (with errors recorded) when a required answer is missing. */
    private static Map typeBlock(ManagedType type, Set<String> selected, GeneratorOptions options, GenerationResult result) {
        String name = type.name
        String kind = options.entityKinds?.get(name)
        String idProperty = options.idProperties?.get(name) ?: defaultIdProperty(type)
        boolean ok = true
        if (!kind) {
            result.errors << "${name}: entityKind is required: answer 'subject' or 'resource' for it".toString()
            ok = false
        }
        if (!idProperty) {
            result.errors << "${name}: idProperty is required: no natural key or userName-like property was found, so name the property that holds the remote ID (A3)".toString()
            ok = false
        } else if (!type.properties.containsKey(idProperty)) {
            result.errors << "${name}: idProperty '${idProperty}' is not a single-valued property of ${name}; its properties are: ${type.properties.keySet().join(', ')}".toString()
            ok = false
        }
        type.excludedProperties.each { String property, String reason ->
            result.warnings << "${name}.${property} is not synchronised: ${reason}".toString()
        }
        if (!ok) return null

        Map block = new LinkedHashMap()
        block.idmResource = "managed/${name}".toString()
        block.entityKind = kind
        block.idProperty = idProperty
        block.attributes = type.properties.keySet().findAll { it != idProperty } as List<String>
        block.archetypes = new ArrayList<String>(options.archetypes?.get(name) ?: [name.capitalize()])
        Map relationships = new LinkedHashMap()
        type.relationships.each { ManagedType.ManagedRelationship rel ->
            relationships[rel.property] = relationshipEntry(name, rel, selected, options, result)
        }
        if (relationships) block.relationships = relationships
        block
    }

    private static Map relationshipEntry(String typeName, ManagedType.ManagedRelationship rel, Set<String> selected,
                                         GeneratorOptions options, GenerationResult result) {
        String at = "${typeName}.${rel.property}"
        String collection = rel.resourceCollections ? rel.resourceCollections[0] : null
        // Only managed objects can be configured types; internal/role and the like never are.
        String relatedType = collection?.startsWith('managed/') ? collection.substring('managed/'.length()) : null
        if (rel.resourceCollections.size() > 1) {
            result.warnings << "${at} may reference ${rel.resourceCollections.join(', ')}; relatedType is taken from the first. Check the reverse side by hand".toString()
        }
        Map override = options.relationshipOverrides?.get(typeName)?.get(rel.property) as Map
        Map entry = new LinkedHashMap()
        if (override) {
            entry.putAll(override)
            if (relatedType && !(relatedType in selected)) {
                result.warnings << "${at} relates to '${relatedType}', which is not selected: its relationships will be skipped by the mapping library until it is".toString()
            }
        } else if (relatedType == null || !(relatedType in selected)) {
            // No edge can be built to an unconfigured type, so there is nothing to resolve: exclude it.
            result.warnings << "${at} relates to ${relatedType ? "'" + relatedType + "', which is not selected" : (collection ?: 'no managed type')}: it is excluded with sync: false".toString()
            entry.sync = false
        } else if (!rel.reversePropertyName) {
            result.warnings << "${at} has no reverse property, so it is not bidirectional (design A2): it is excluded with sync: false".toString()
            entry.sync = false
        } else {
            RelationshipConvention.PairResolution pair = RelationshipConvention.resolvePair(rel.property, rel.reversePropertyName)
            if (pair.resolved) {
                entry.remoteType = pair.remoteType
                entry.direction = pair.outboundProperty == rel.property ? 'outbound' : 'inbound'
            } else {
                result.errors << "${at} and its reverse '${rel.reversePropertyName}' cannot be resolved by the convention (§7): ${pair.reason}. Add an override with remoteType and direction for both".toString()
            }
        }
        if (relatedType && !entry.containsKey('relatedType')) entry.relatedType = relatedType
        if (rel.reversePropertyName && !entry.containsKey('reverseProperty')) entry.reverseProperty = rel.reversePropertyName
        if (entry.sync == true) entry.remove('sync')
        entry
    }

    /** Default idProperty choice: first natural-key candidate, else a userName-like property, else null (must be prompted). */
    static String defaultIdProperty(ManagedType type) {
        if (type.naturalKeyCandidates) return type.naturalKeyCandidates[0]
        USERNAME_LIKE.find { type.properties.containsKey(it) }
    }
}
