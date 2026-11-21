package au.com.azwork.idm.graphentity.search

import au.com.azwork.idm.graphentity.http.HttpResponse
import groovy.transform.Canonical

/**
 * Paging state carried through ICF's paged-results cookie (design §9.4). The service pages with an opaque
 * {@code pagedResultsCookie}: send back the one the previous response returned, and stop when it is null
 * ({@code hasMore} false). The ICF cookie wraps the service's cookie (URL-safe base64 of compact JSON), so a
 * corrupt ICF cookie can be detected rather than silently restarting from the first page. All paging knowledge
 * stays in this class (P6 superseded).
 */
@Canonical
class PageCursor {
    /** The service's cookie for the next page; null for the first page. */
    String serviceCookie

    /** TODO WS4. Null/blank cookie → first page. Anything undecodable → IllegalArgumentException. */
    static PageCursor decode(String cookie) {
        throw new UnsupportedOperationException('TODO WS4: PageCursor.decode')
    }

    /** TODO WS4. */
    String encode() {
        throw new UnsupportedOperationException('TODO WS4: PageCursor.encode')
    }

    /** Request-body fields for this page: {@code pageSize}, plus {@code pagedResultsCookie} after the first page. TODO WS4. */
    Map<String, Object> toQuery(int pageSize) {
        throw new UnsupportedOperationException('TODO WS4: PageCursor.toQuery')
    }

    /** Cursor for the next page from a search response; null when {@code pagedResultsCookie} is null. TODO WS4. */
    static PageCursor next(HttpResponse response) {
        throw new UnsupportedOperationException('TODO WS4: PageCursor.next')
    }

    /** {@code totalResults} from a search response, or -1 when absent or unknown. TODO WS4. */
    static int totalResults(HttpResponse response) {
        throw new UnsupportedOperationException('TODO WS4: PageCursor.totalResults')
    }
}
