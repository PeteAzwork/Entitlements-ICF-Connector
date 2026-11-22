package au.com.azwork.idm.entitlementsaccelerator.payload

import au.com.azwork.idm.entitlementsaccelerator.model.Edge

/**
 * Post-create verification (design §9.1): compares the submitted edge set with the set returned by the
 * verification GET and reports every dropped edge so it can be logged at WARN.
 */
class EdgeVerifier {

    /** Edges in {@code submitted} that are absent from {@code returned}, compared as a set. */
    static List<Edge> dropped(Collection<Edge> submitted, Collection<Edge> returned) {
        Set<Edge> present = new HashSet<>(returned ?: [])
        new ArrayList<Edge>(new LinkedHashSet<Edge>((submitted ?: []).findAll { !(it in present) }))
    }

    /**
     * WARN message of the form {@code dropped edge Sanne -Owns-> Current-Account-1-Sanne}: source, type,
     * target, whichever end is being written. Contains IDs and type only, never attributes.
     */
    static String warnMessage(Edge edge) {
        "dropped edge ${edge.sourceId} -${edge.type}-> ${edge.targetId}"
    }
}
