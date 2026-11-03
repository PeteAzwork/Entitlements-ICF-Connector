package au.com.azwork.idm.graphentity.search

import groovy.transform.Canonical

/** What a search filter translates to (design §9.4). */
@Canonical
class SearchPlan {
    enum Kind {
        /** EqualsFilter on __UID__/__NAME__ → GET /admin/entities/{id} */
        BY_ID,
        /** GreaterThan(OrEqual) on lastUpdated → timestamp search from the given instant */
        SINCE,
        /** No filter → timestamp search from epoch, paged */
        ENUMERATE
    }

    Kind kind
    String id
    /** ISO-8601 instant for SINCE/ENUMERATE. */
    String since
    int pageSize
    PageCursor cursor
}
