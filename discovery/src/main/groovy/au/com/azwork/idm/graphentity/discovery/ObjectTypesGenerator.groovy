package au.com.azwork.idm.graphentity.discovery

/**
 * Generates the connector {@code objectTypes} block from managed types (design §12), applying the §7
 * convention to every relationship pair, flagging what the convention cannot resolve, and running the
 * same consistency validation the connector runs at load time.
 */
class ObjectTypesGenerator {

    /** TODO WS6. */
    static GenerationResult generate(List<ManagedType> types, GeneratorOptions options) {
        throw new UnsupportedOperationException('TODO WS6: ObjectTypesGenerator.generate')
    }

    /** Default idProperty choice: first natural-key candidate, else a userName-like property, else null (must be prompted). TODO WS6. */
    static String defaultIdProperty(ManagedType type) {
        throw new UnsupportedOperationException('TODO WS6: ObjectTypesGenerator.defaultIdProperty')
    }
}
