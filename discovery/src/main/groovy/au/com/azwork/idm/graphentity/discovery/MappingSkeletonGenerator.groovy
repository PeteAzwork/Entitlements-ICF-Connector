package au.com.azwork.idm.graphentity.discovery

/**
 * Generates a {@code sync.json} mapping entry per type (design §12): managed/<type> → system/<connector>/<type>,
 * property mappings for every configured attribute, {@code entityRelationships}/{@code directArchetypes} bound
 * to the shared library transformations, correlation on the idProperty, and conservative situational policies
 * (target-phase TARGET-only → REPORT per §11).
 */
class MappingSkeletonGenerator {

    static final String LIBRARY_PATH = 'lib/graphEntityPayload.js'

    /** TODO WS6. */
    static Map mapping(String typeName, Map objectType, GeneratorOptions options) {
        throw new UnsupportedOperationException('TODO WS6: MappingSkeletonGenerator.mapping')
    }
}
