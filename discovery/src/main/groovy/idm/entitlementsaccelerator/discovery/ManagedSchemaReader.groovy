package idm.entitlementsaccelerator.discovery

import groovy.json.JsonSlurper

/**
 * Reads IDM's {@code config/managed} document into {@link ManagedType}s. Excludes by default: relationship
 * properties (surfaced separately), virtual properties ({@code isVirtual}), password-class properties
 * (name {@code password} or {@code "isProtected": true}), and the private {@code _*} fields.
 * Array-typed properties that are not relationships go to {@link ManagedType#excludedProperties}: the service
 * stores single-valued strings only (decision P20). So do object-typed properties.
 *
 * <p>Natural keys (WS6.01, proposed 2026-11-21, to be agreed with the platform team): managed.json has no index
 * declaration, so a property is a natural-key candidate when its {@code policies} include IDM's {@code unique} or
 * {@code valid-username} policy. {@code userName} carries {@code valid-username} in IDM's default managed.json.
 * Change {@link #NATURAL_KEY_POLICIES} if the platform team settles on another signal.
 */
class ManagedSchemaReader {

    static final Set<String> NATURAL_KEY_POLICIES = ['unique', 'valid-username'] as Set

    static List<ManagedType> read(Map managedJson) {
        Object objects = managedJson?.objects
        if (!(objects instanceof List)) throw new IllegalArgumentException('managed.json must have an "objects" array')
        (objects as List).collect { Object o ->
            if (!(o instanceof Map) || !(o as Map).name) throw new IllegalArgumentException('Every managed object needs a name')
            readType(o as Map)
        }
    }

    static List<ManagedType> read(String managedJson) {
        Object parsed = new JsonSlurper().parseText(managedJson)
        if (!(parsed instanceof Map)) throw new IllegalArgumentException('managed.json must be a JSON object')
        read(parsed as Map)
    }

    private static ManagedType readType(Map object) {
        ManagedType type = new ManagedType(name: object.name as String)
        Map props = (object.schema instanceof Map ? (object.schema as Map).properties : null) as Map ?: [:]
        props.each { k, v ->
            String name = k as String
            Map definition = v instanceof Map ? v as Map : [:]
            if (name.startsWith('_') || definition.isVirtual == true) return
            Map items = definition.items instanceof Map ? definition.items as Map : null
            if (definition.type == 'relationship' || (definition.type == 'array' && items?.type == 'relationship')) {
                Map rel = definition.type == 'relationship' ? definition : items
                type.relationships << new ManagedType.ManagedRelationship(
                    property: name,
                    reversePropertyName: rel.reversePropertyName as String,
                    resourceCollections: (rel.resourceCollection instanceof List ? rel.resourceCollection : [])
                        .collect { it instanceof Map ? (it as Map).path as String : null }.findAll { it } as List<String>,
                    multiValued: definition.type == 'array')
                return
            }
            if (name == 'password' || definition.isProtected == true) return
            if (definition.type == 'array') {
                type.excludedProperties[name] = 'multi-valued (array) property: the service stores single-valued strings (P20)'
                return
            }
            if (definition.type == 'object') {
                type.excludedProperties[name] = 'object property: the service stores single-valued strings (P20)'
                return
            }
            type.properties[name] = definition
            List policies = definition.policies instanceof List ? definition.policies as List : []
            if (policies.any { it instanceof Map && (it as Map).policyId in NATURAL_KEY_POLICIES }) type.naturalKeyCandidates << name
        }
        type
    }
}
