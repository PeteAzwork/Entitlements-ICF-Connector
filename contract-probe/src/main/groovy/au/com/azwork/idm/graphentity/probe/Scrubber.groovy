package au.com.azwork.idm.graphentity.probe

/**
 * Removes data belonging to entities the probe did not create before anything is written to disk.
 * Aggregates (counts, archetype names, BaseType values, field names, timestamp formats) are computed from
 * the unscrubbed in-memory responses; only scrubbed copies are persisted.
 *
 * An entity is any map with a string {@code ID}. A foreign entity keeps its shape (BaseType, archetypes,
 * attribute key names, edge types) so recordings stay useful, but every ID and attribute value is replaced.
 */
class Scrubber {

    static final String REDACTED = '<scrubbed>'

    final String probePrefix
    private final Map<String, String> aliases = [:]

    Scrubber(String probePrefix) { this.probePrefix = probePrefix }

    boolean isProbeId(Object id) { id instanceof String && id.startsWith(probePrefix) }

    /** Stable alias per foreign ID, so relationships between scrubbed entities still line up. */
    String alias(String id) {
        isProbeId(id) ? id : aliases.computeIfAbsent(id) { "scrubbed-${aliases.size() + 1}".toString() }
    }

    /** Replaces a foreign entity ID in an entity path, e.g. /admin/entities/Sanne → /admin/entities/scrubbed-1. */
    String scrubPath(String rawPath) {
        String prefix = '/admin/entities/'
        if (rawPath == null || !rawPath.startsWith(prefix) || rawPath.length() == prefix.length()) return rawPath
        String id = URLDecoder.decode(rawPath.substring(prefix.length()).replace('+', '%2B'), 'UTF-8')
        isProbeId(id) ? rawPath : prefix + alias(id)
    }

    Object scrub(Object node) {
        if (node instanceof Map) {
            Map m = node as Map
            if (m.ID instanceof String && !isProbeId(m.ID)) return scrubForeignEntity(m)
            return m.collectEntries { k, v ->
                if ((k == 'TargetID' || k == 'SourceID') && v instanceof String) return [(k): alias(v as String)]
                [(k): scrub(v)]
            }
        }
        if (node instanceof List) return (node as List).collect { scrub(it) }
        node
    }

    private Map scrubForeignEntity(Map m) {
        m.collectEntries { k, v ->
            switch (k) {
                case 'ID': return [(k): alias(v as String)]
                case 'attributes':
                    return [(k): (v instanceof Map ? (v as Map).collectEntries { ak, av -> [(ak): ak == 'ID' ? alias(m.ID as String) : REDACTED] } : REDACTED)]
                case 'Relationships':
                    return [(k): (v instanceof List ? (v as List).collect { e ->
                        e instanceof Map ? (e as Map).collectEntries { ek, evv ->
                            [(ek): (ek == 'TargetID' || ek == 'SourceID') && evv instanceof String ? alias(evv as String) : (ek == 'type' ? evv : REDACTED)]
                        } : REDACTED
                    } : REDACTED)]
                case 'BaseType':
                case 'directArchetypes':
                    return [(k): v]
                default:
                    // Keep timestamps' format visible (useful for WS0.06), drop anything else.
                    return [(k): (v instanceof String && v ==~ /\d{4}-\d{2}-\d{2}T.*/) ? v : REDACTED]
            }
        }
    }
}
