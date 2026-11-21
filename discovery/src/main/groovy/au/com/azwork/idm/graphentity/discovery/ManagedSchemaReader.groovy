package au.com.azwork.idm.graphentity.discovery

/**
 * Reads IDM's {@code config/managed} document into {@link ManagedType}s. Excludes by default: relationship
 * properties (surfaced separately), virtual properties ({@code isVirtual}), password-class properties
 * (name {@code password} or {@code "type": "string", "isProtected": true}), and the private {@code _*} fields.
 * Array-typed properties that are not relationships go to {@link ManagedType#excludedProperties}: the service
 * stores single-valued strings only (decision P20).
 */
class ManagedSchemaReader {

    /** TODO WS6. */
    static List<ManagedType> read(Map managedJson) {
        throw new UnsupportedOperationException('TODO WS6: ManagedSchemaReader.read')
    }

    /** TODO WS6. */
    static List<ManagedType> read(String managedJson) {
        throw new UnsupportedOperationException('TODO WS6: ManagedSchemaReader.read(String)')
    }
}
