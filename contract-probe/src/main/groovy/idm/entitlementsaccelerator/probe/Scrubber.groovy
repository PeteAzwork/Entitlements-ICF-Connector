package idm.entitlementsaccelerator.probe

/**
 * Removes data belonging to entities the probe did not create before anything is written to disk.
 * Aggregates (counts, archetype names, base types, field names, timestamp formats) are computed from the
 * unscrubbed in-memory responses; only scrubbed copies are persisted.
 *
 * <p>Fails closed. An entity is any map with a string {@code ID}, {@code id} or {@code uid} (the service's
 * write model, read model and search results use different casings), and every map inside a search result
 * list ({@code results}, {@code items}, ...) is treated as an entity even without an ID field. A foreign entity
 * keeps its shape (base type, archetypes, attribute and field names, relationship types, timestamp formats)
 * so recordings stay useful, but every identifier and attribute value is replaced.
 *
 * <p>Two non-entity shapes also carry data: a search filter's {@code right} value (an entity ID, or an attribute
 * value) is aliased or redacted unless it is a probe value, and a manifest list is reduced to archetype names,
 * because manifests can name entities in their instance lists.
 */
class Scrubber {

    static final String REDACTED = '<scrubbed>'

    /** Keys whose value identifies an entity, in any of the service's casings. */
    static final List<String> ID_KEYS = ['ID', 'id', 'Id', 'uid', 'UID']
    /** Keys that hold another entity's ID inside a relationship, in every casing the API uses. */
    static final List<String> EDGE_ID_KEYS = ['TargetID', 'SourceID', 'targetID', 'sourceID', 'target', 'source', 'targetId', 'sourceId']
    /** Keys of a foreign entity that are safe to keep as-is: types and classifications, not data. */
    static final List<String> KEEP_KEYS = ['BaseType', 'baseType', 'directArchetypes', 'isArchetypes', 'type', 'sourceType']
    /** Keys whose values are attribute maps or lists. */
    static final List<String> ATTRIBUTE_KEYS = ['attributes', 'Attributes', 'compoundAttributes']
    /** Keys whose values are relationship lists. */
    static final List<String> RELATIONSHIP_KEYS = ['Relationships', 'relationships']
    /** Envelope keys whose list elements are entities. */
    static final List<String> RESULT_LIST_KEYS = ['results', 'items', 'data', 'entities', 'resources', 'content', 'value', 'result']
    /** The manifests endpoint: its response is reduced to archetype names (see {@link #scrubManifests}). */
    static final String MANIFESTS_PATH = '/admin/manifests'
    /** Path segments under /admin/entities/ that are endpoint names, not IDs. */
    static final Set<String> PATH_KEYWORDS = ['search', 'bulk', 'relationships', 'direct-assign-archetype', 'delegations'] as Set

    final String probePrefix
    /** IDs under this namespace were made by some probe run (possibly an earlier one) and hold no real data. */
    final String probeNamespace
    /** Whole paths under /admin/entities/ that are endpoints (the search endpoint). */
    final Set<String> endpointPaths
    private final Map<String, String> aliases = [:]

    Scrubber(String probePrefix, Collection<String> endpointPaths = ['/admin/entities/search'], String probeNamespace = null) {
        this.probePrefix = probePrefix
        this.endpointPaths = endpointPaths as Set<String>
        this.probeNamespace = probeNamespace
    }

    /** True for this run's IDs, and for any earlier run's when a namespace is set. */
    boolean isProbeId(Object id) {
        id instanceof String && (id.startsWith(probePrefix) || (probeNamespace && id.startsWith(probeNamespace)))
    }

    /** The entity's identity in whichever casing it carries, or null. */
    static String identity(Map m) {
        def key = ID_KEYS.find { m[it] instanceof String }
        key ? m[key] as String : null
    }

    /** Stable alias per foreign ID, so relationships between scrubbed entities still line up. */
    String alias(String id) {
        isProbeId(id) ? id : aliases.computeIfAbsent(id) { "scrubbed-${aliases.size() + 1}".toString() }
    }

    /**
     * Replaces foreign IDs in an entity path. Every segment that is not an endpoint keyword or a probe ID is
     * aliased, so relationship paths such as /admin/entities/{source}/relationships/{target}/{type} are covered.
     */
    String scrubPath(String rawPath) {
        String prefix = '/admin/entities/'
        if (rawPath == null || !rawPath.startsWith(prefix) || rawPath.length() == prefix.length() || rawPath in endpointPaths) return rawPath
        rawPath.substring(prefix.length()).split('/', -1).collect { String seg ->
            if (seg.isEmpty() || seg in PATH_KEYWORDS) return seg
            String id = URLDecoder.decode(seg.replace('+', '%2B'), 'UTF-8')
            isProbeId(id) ? seg : alias(id)
        }.join('/').with { prefix + it }
    }

    /** A response body, scrubbed according to the endpoint it came from. */
    Object scrubResponse(String path, Object body) {
        path == MANIFESTS_PATH ? scrubManifests(body) : scrub(body)
    }

    /**
     * Keeps manifest and archetype names, and the structure that leads to them; drops everything else. Handles the
     * documented list of manifests and the export bundle the service actually returns (C11), where the manifests sit
     * under {@code paths[*].manifests}. Fails closed: an object under any other key (such as {@code entities} or
     * {@code policies}, which could be keyed by entity IDs) is replaced whole, keys included.
     */
    static Object scrubManifests(Object body) { keepNames(body, true) }

    private static final Set<String> MANIFEST_CONTAINERS = ['paths', 'manifests'] as Set
    private static final Set<String> PROBLEM_KEYS = ['title', 'status', 'type'] as Set

    private static Object keepNames(Object n, boolean root, boolean inManifests = false) {
        if (n instanceof List) return (n as List).collect { it instanceof Map || it instanceof List ? keepNames(it, root, inManifests) : REDACTED }
        if (!(n instanceof Map)) return n == null ? null : REDACTED
        (n as Map).collectEntries { k, v ->
            String key = k as String
            if (key == 'name' && !(v instanceof Map || v instanceof List)) return [(k): v]
            if (key.endsWith('Archetypes') && v instanceof List) return [(k): (v as List).collect { a -> a instanceof Map ? [name: (a as Map).name] : REDACTED }]
            if (root && key in PROBLEM_KEYS && !(v instanceof Map || v instanceof List)) return [(k): v]
            if ((key in MANIFEST_CONTAINERS || inManifests) && (v instanceof Map || v instanceof List)) return [(k): keepNames(v, false, inManifests || key == 'manifests')]
            [(k): v == null ? null : REDACTED]
        }
    }

    /** A filter node, {operation, left, right, not}. Its right-hand value may be an ID or attribute data. */
    private static boolean isFilter(Map m) { m.containsKey('operation') && m.containsKey('left') }

    Object scrub(Object node) {
        if (node instanceof Map) {
            Map m = node as Map
            String id = identity(m)
            if (id != null && !isProbeId(id)) return scrubForeignEntity(m)
            if (isFilter(m) && m.right instanceof String && !isProbeId(m.right)) {
                boolean onId = (m.left as String)?.toLowerCase() in ['id', 'uid']
                return m.collectEntries { k, v -> [(k): k == 'right' ? (onId ? alias(v as String) : REDACTED) : scrub(v)] }
            }
            return m.collectEntries { k, v ->
                if (k in EDGE_ID_KEYS && v instanceof String) return [(k): alias(v as String)]
                if (k in RESULT_LIST_KEYS && v instanceof List) {
                    // Fail closed: an element of a result list is an entity even if it carries no recognisable ID.
                    return [(k): (v as List).collect { e -> e instanceof Map && !isProbeId(identity(e as Map)) ? scrubForeignEntity(e as Map) : scrub(e) }]
                }
                [(k): scrub(v)]
            }
        }
        if (node instanceof List) return (node as List).collect { scrub(it) }
        node
    }

    private Map scrubForeignEntity(Map m) {
        String self = identity(m)
        m.collectEntries { k, v ->
            if (k in ID_KEYS || k == 'name') return [(k): v instanceof String ? alias(v as String) : REDACTED]
            if (k in KEEP_KEYS) return [(k): v]
            if (k in ATTRIBUTE_KEYS) return [(k): redactAttributes(v, self)]
            if (k in RELATIONSHIP_KEYS) return [(k): v instanceof List ? (v as List).collect { scrubForeignEdge(it) } : REDACTED]
            // Keep a timestamp's format visible (useful for WS0.06); drop anything else.
            [(k): (v instanceof String && v ==~ /\d{4}-\d{2}-\d{2}T.*/) ? v : REDACTED]
        }
    }

    /** Keeps attribute names, redacts values: plain values, the read model's {name, type, value} objects, and lists. */
    private Object redactAttributes(Object v, String self) {
        if (v instanceof Map && isAttributeObject(v as Map)) return redactAttributeObject(v as Map)
        if (v instanceof Map) {
            return (v as Map).collectEntries { ak, av ->
                if (ak in ID_KEYS && av instanceof String) return [(ak): self ? alias(self) : REDACTED]
                if (av instanceof Map) return [(ak): isAttributeObject(av as Map) ? redactAttributeObject(av as Map) : redactAttributes(av, self)]
                if (av instanceof List) return [(ak): (av as List).collect { redactAttributes(it, self) }]
                [(ak): REDACTED]
            }
        }
        if (v instanceof List) return (v as List).collect { redactAttributes(it, self) }
        REDACTED
    }

    /** The read model's attribute object: {name, type, value}. */
    private static boolean isAttributeObject(Map m) {
        m.containsKey('value') && m.keySet().every { it in ['name', 'type', 'value'] }
    }

    /** Keeps an attribute object's name and type, redacts its value. */
    private static Map redactAttributeObject(Map m) {
        m.collectEntries { fk, fv -> [(fk): fk in ['name', 'type'] ? fv : REDACTED] }
    }

    private Object scrubForeignEdge(Object e) {
        if (!(e instanceof Map)) return REDACTED
        (e as Map).collectEntries { ek, ev ->
            if (ek in EDGE_ID_KEYS && ev instanceof String) return [(ek): alias(ev as String)]
            [(ek): ek in KEEP_KEYS ? ev : REDACTED]
        }
    }
}
