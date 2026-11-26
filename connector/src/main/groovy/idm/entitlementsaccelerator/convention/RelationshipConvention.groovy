package idm.entitlementsaccelerator.convention

import idm.entitlementsaccelerator.config.Direction
import idm.entitlementsaccelerator.config.ObjectTypeConfig
import idm.entitlementsaccelerator.config.RelationshipConfig
import groovy.transform.Canonical
import org.identityconnectors.framework.common.exceptions.ConfigurationException

/**
 * Normative relationship direction and type convention (design §7).
 *
 * <ul>
 *   <li>{@link #normalise}: upper-case the first letter and strip a trailing {@code Set}/{@code List}.</li>
 *   <li>{@link #isPassive}: the passive side of a pair is recognised by a trailing {@code By}
 *       (e.g. {@code ownedBy}); the opposite side is outbound and its normalised name is the remote type.</li>
 *   <li>{@link #resolvePair}: resolves a (P, Q) pair, or reports it unresolved so an override is required.</li>
 *   <li>{@link #validate}: load-time consistency check for a whole {@code objectTypes} document.</li>
 * </ul>
 */
class RelationshipConvention {

    @Canonical
    static class PairResolution {
        boolean resolved
        String remoteType
        /** Property name on the outbound side. */
        String outboundProperty
        /** Property name on the inbound side. */
        String inboundProperty
        /** Human-readable reason when unresolved. */
        String reason
    }

    @Canonical
    static class Resolved {
        String remoteType
        Direction direction
        boolean sync = true
    }

    /** A capitalised {@code Set}/{@code List} suffix with at least one character before it. */
    private static final java.util.regex.Pattern COLLECTION_SUFFIX = ~/^(.+?)(Set|List)$/

    /** {@code owns → Owns}, {@code ownsSet → Owns}, {@code memberList → Member}, {@code ownedBy → OwnedBy}. */
    static String normalise(String propertyName) {
        String base = stripSuffix(propertyName)
        base.substring(0, 1).toUpperCase() + base.substring(1)
    }

    /**
     * True for the passive form of a pair name, e.g. {@code ownedBy}, {@code managedBy}: after any {@code Set}/
     * {@code List} suffix is removed, the name ends in a capital {@code By} with something before it.
     */
    static boolean isPassive(String propertyName) {
        stripSuffix(propertyName) ==~ /^.+By$/
    }

    private static String stripSuffix(String propertyName) {
        if (propertyName == null || !propertyName.trim()) throw new IllegalArgumentException('A relationship property name must not be blank')
        String name = propertyName.trim()
        def m = COLLECTION_SUFFIX.matcher(name)
        m.matches() ? m.group(1) : name
    }

    /**
     * Resolves a property pair by convention. Exactly one side must be passive; the other side is
     * outbound and supplies the remote type. Both-passive, neither-passive, a self-referential pair or a missing
     * reverse property yield {@code resolved == false} with a reason. The result does not depend on argument order.
     */
    static PairResolution resolvePair(String property, String reverseProperty) {
        if (property == null || !property.trim()) return unresolved('the property name is blank')
        if (reverseProperty == null || !reverseProperty.trim()) {
            return unresolved("'${property}' has no reverse property, so it is not bidirectional (design A2)")
        }
        String p = property.trim(), q = reverseProperty.trim()
        if (p == q) return unresolved("'${p}' is its own reverse, which gives no direction")
        boolean pPassive = isPassive(p), qPassive = isPassive(q)
        List<String> pair = [p, q].sort()
        if (pPassive == qPassive) {
            return unresolved("${pPassive ? 'both' : 'neither'} of '${pair[0]}' and '${pair[1]}' ${pPassive ? 'are' : 'is'} passive (ending in 'By'), so the convention cannot tell which is the source")
        }
        String active = pPassive ? q : p
        String passive = pPassive ? p : q
        new PairResolution(resolved: true, remoteType: normalise(active), outboundProperty: active, inboundProperty: passive)
    }

    private static PairResolution unresolved(String reason) { new PairResolution(resolved: false, reason: reason) }

    /**
     * Resolves the remote type and direction for a single property on a type: an explicit override wins
     * absolutely; otherwise the convention is applied to (property, reverseProperty), taking the reverse property
     * from the entry's hint when the argument is null. Returns null when {@code sync == false}. Throws
     * ConfigurationException when neither override nor convention resolves it.
     */
    static Resolved resolve(String property, String reverseProperty, RelationshipConfig override) {
        if (override != null && !override.sync) return null
        if (override?.remoteType && override?.direction) return new Resolved(remoteType: override.remoteType, direction: override.direction)
        String reverse = reverseProperty ?: override?.reverseProperty
        PairResolution pr = resolvePair(property, reverse)
        if (!pr.resolved) {
            throw new ConfigurationException("Relationship '${property}' (reverse '${reverse ?: 'none'}') cannot be resolved by the convention (design §7): ${pr.reason}. Declare remoteType and direction for it explicitly.")
        }
        new Resolved(remoteType: pr.remoteType, direction: pr.outboundProperty == property.trim() ? Direction.OUTBOUND : Direction.INBOUND)
    }

    /**
     * §7 consistency requirement across a whole configuration. For every relationship entry whose
     * {@code relatedType} and {@code reverseProperty} identify a counterpart entry in the configuration:
     * exactly one side must be OUTBOUND and the other INBOUND, and both must carry the same remoteType.
     * Two entries on the same type with the same remoteType, direction and relatedType are a collision.
     * Throws ConfigurationException naming every violation; returns non-fatal warnings (the counterpart type is
     * not configured, or an entry carries no hints to find its counterpart by).
     */
    static List<String> validate(Map<String, ObjectTypeConfig> objectTypes) {
        Set<String> violations = new LinkedHashSet<>()
        List<String> warnings = []
        // type name -> property -> resolution, for synced entries that resolve
        Map<String, Map<String, Resolved>> resolved = [:]
        objectTypes.each { String typeName, ObjectTypeConfig type ->
            Map<String, Resolved> byProperty = new LinkedHashMap<>()
            type.syncedRelationships().each { String property, RelationshipConfig entry ->
                try {
                    Resolved r = resolve(property, null, entry)
                    if (r != null) byProperty[property] = r
                } catch (ConfigurationException e) {
                    violations << "${typeName}.${property}: ${e.message}".toString()
                }
            }
            resolved[typeName] = byProperty
        }

        objectTypes.each { String typeName, ObjectTypeConfig type ->
            resolved[typeName].each { String property, Resolved r ->
                RelationshipConfig entry = type.relationships[property]
                if (!entry.relatedType || !entry.reverseProperty) {
                    warnings << "${typeName}.${property} has no relatedType and reverseProperty, so its counterpart cannot be checked (design §7)".toString()
                    return
                }
                ObjectTypeConfig other = objectTypes[entry.relatedType]
                if (other == null) {
                    warnings << "${typeName}.${property} relates to type '${entry.relatedType}', which is not configured, so the pair cannot be checked".toString()
                    return
                }
                Resolved counterpart = resolved[entry.relatedType][entry.reverseProperty]
                if (counterpart == null) return   // not configured, not synced, or already reported as unresolvable
                String a = "${typeName}.${property}", b = "${entry.relatedType}.${entry.reverseProperty}"
                List<String> pair = [a, b].sort()
                if (r.direction == counterpart.direction) {
                    violations << "${pair[0]} and ${pair[1]} are both ${r.direction.name().toLowerCase()}: one side of a pair must be outbound and the other inbound".toString()
                }
                if (r.remoteType != counterpart.remoteType) {
                    Map<String, String> types = [(a): r.remoteType, (b): counterpart.remoteType]
                    violations << "${pair[0]} uses remoteType '${types[pair[0]]}' but ${pair[1]} uses '${types[pair[1]]}': both sides of a pair must use the same type".toString()
                }
            }

            resolved[typeName].findAll { property, r -> type.relationships[property].relatedType }
                .groupBy { property, r -> [r.remoteType, r.direction, type.relationships[property].relatedType] }
                .each { List key, Map<String, Resolved> colliding ->
                    if (colliding.size() > 1) {
                        violations << "${typeName}: ${colliding.keySet().sort().join(' and ')} all resolve to remoteType '${key[0]}', ${(key[1] as Direction).name().toLowerCase()}, towards ${key[2]}, so they would write the same relationships".toString()
                    }
                }
        }

        if (violations) {
            throw new ConfigurationException("Relationship configuration is inconsistent (design §7):\n - " + violations.join('\n - '))
        }
        warnings
    }
}
