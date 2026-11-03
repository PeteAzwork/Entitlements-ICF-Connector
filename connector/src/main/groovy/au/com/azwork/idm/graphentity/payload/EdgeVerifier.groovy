package au.com.azwork.idm.graphentity.payload

import au.com.azwork.idm.graphentity.model.Edge

/**
 * Post-create verification (design §9.1): compares the submitted edge set with the set returned by the
 * verification GET and reports every dropped edge so it can be logged at WARN.
 */
class EdgeVerifier {

    /** Edges in {@code submitted} that are absent from {@code returned}, compared as a set. TODO WS3. */
    static List<Edge> dropped(Collection<Edge> submitted, Collection<Edge> returned) {
        throw new UnsupportedOperationException('TODO WS3: EdgeVerifier.dropped')
    }

    /**
     * WARN message of the form {@code dropped edge Sanne -Owns-> Current-Account-1-Sanne} (outbound) or
     * {@code dropped edge Sanne <-Owns- Other} (inbound). Contains IDs and type only, never attributes. TODO WS3.
     */
    static String warnMessage(String entityId, Edge edge) {
        throw new UnsupportedOperationException('TODO WS3: EdgeVerifier.warnMessage')
    }
}
