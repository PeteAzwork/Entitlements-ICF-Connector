package au.com.azwork.idm.graphentity.convention

import au.com.azwork.idm.graphentity.config.Direction
import au.com.azwork.idm.graphentity.config.ObjectTypeConfig
import au.com.azwork.idm.graphentity.config.RelationshipConfig
import groovy.transform.Canonical

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

    /** {@code owns → Owns}, {@code ownsSet → Owns}, {@code memberList → Member}, {@code ownedBy → OwnedBy}. TODO WS1. */
    static String normalise(String propertyName) {
        throw new UnsupportedOperationException('TODO WS1: RelationshipConvention.normalise')
    }

    /** True for the passive form of a pair name, e.g. {@code ownedBy}, {@code managedBy}. TODO WS1. */
    static boolean isPassive(String propertyName) {
        throw new UnsupportedOperationException('TODO WS1: RelationshipConvention.isPassive')
    }

    /**
     * Resolves a property pair by convention. Exactly one side must be passive; the other side is
     * outbound and supplies the remote type. Both-passive, neither-passive or missing reverse property
     * yield {@code resolved == false} with a reason. TODO WS1.
     */
    static PairResolution resolvePair(String property, String reverseProperty) {
        throw new UnsupportedOperationException('TODO WS1: RelationshipConvention.resolvePair')
    }

    /**
     * Resolves the remote type and direction for a single property on a type: an explicit override wins
     * absolutely; otherwise the convention is applied to (property, reverseProperty). Returns null when
     * {@code sync == false}. Throws ConfigurationException when neither override nor convention resolves it.
     * TODO WS1.
     */
    static Resolved resolve(String property, String reverseProperty, RelationshipConfig override) {
        throw new UnsupportedOperationException('TODO WS1: RelationshipConvention.resolve')
    }

    /**
     * §7 consistency requirement across a whole configuration. For every relationship entry whose
     * {@code relatedType} and {@code reverseProperty} identify a counterpart entry in the configuration:
     * exactly one side must be OUTBOUND and the other INBOUND, and both must carry the same remoteType.
     * Two entries on the same type with the same remoteType, direction and relatedType are a collision.
     * Throws ConfigurationException naming every violation; returns non-fatal warnings (e.g. the
     * counterpart type is not configured). TODO WS1.
     */
    static List<String> validate(Map<String, ObjectTypeConfig> objectTypes) {
        throw new UnsupportedOperationException('TODO WS1: RelationshipConvention.validate')
    }
}
