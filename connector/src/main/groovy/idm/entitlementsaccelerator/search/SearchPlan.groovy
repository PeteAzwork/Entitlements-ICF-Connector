package idm.entitlementsaccelerator.search

import groovy.transform.Canonical

/** What a search filter translates to (design §9.4; decision P21). */
@Canonical
class SearchPlan {
    enum Kind {
        /** EqualsFilter on __UID__/__NAME__ → a read by ID (GET, or a search on the ID with byIdLookup: search). */
        BY_ID,
        /**
         * Any other supported filter, or none → a search POST with the type's {@code objectClass} and, when
         * present, {@link #filter}. Timestamp ("since") searches do not exist: the service has none, and drift
         * detection is deferred to WS10 (P16).
         */
        ENUMERATE
    }

    Kind kind
    String id
    /** The service's filter tree, {@code {operation, left, right, not}}, or null for no filter. */
    Map<String, Object> filter
    int pageSize
    PageCursor cursor
}
