package au.com.azwork.idm.graphentity.config

import groovy.transform.Canonical

/**
 * One entry of an object type's {@code relationships} block (design §6, §7).
 *
 * {@code relatedType} and {@code reverseProperty} are emitted by the discovery script so that the
 * connector can run the §7 consistency validation at load time without access to managed.json.
 * They are optional for hand-written configuration.
 */
@Canonical
class RelationshipConfig {
    /** IDM relationship property name on this type, e.g. {@code owns}. */
    String property
    /** Remote relationship {@code type}, e.g. {@code Owns}. */
    String remoteType
    Direction direction
    /** {@code "sync": false} excludes the property from synchronisation entirely. */
    boolean sync = true
    /** Name of the object type (connector-side) on the other end, e.g. {@code account}. Optional. */
    String relatedType
    /** {@code reversePropertyName} of the IDM property. Optional. */
    String reverseProperty
    /** True when remoteType/direction were declared explicitly rather than derived by convention. */
    boolean explicit = false
}
