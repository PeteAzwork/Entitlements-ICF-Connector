package au.com.azwork.idm.graphentity.discovery

/**
 * Diff-style summary between the live {@code objectTypes} configuration and a freshly generated proposal
 * (design §12: schema evolution is regenerate-plus-review).
 */
class DiffSummary {

    /** Lines prefixed with '+', '-' or '~' (added, removed, changed) per type/attribute/relationship. TODO WS6. */
    static List<String> summarise(Map current, Map proposed) {
        throw new UnsupportedOperationException('TODO WS6: DiffSummary.summarise')
    }
}
