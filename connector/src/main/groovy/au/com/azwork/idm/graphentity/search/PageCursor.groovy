package au.com.azwork.idm.graphentity.search

import au.com.azwork.idm.graphentity.http.HttpResponse
import groovy.transform.Canonical

/**
 * Paging state carried through ICF's paged-results cookie (design §9.4). The remote paging protocol is
 * unconfirmed (§15), so the cursor is abstracted: it encodes to/decodes from an opaque string and knows how
 * to derive the next cursor from a response. The assumed protocol pins {@code offset}/{@code pageSize}
 * query parameters and a {@code nextCursor}-or-{@code totalCount} response field; see SearchPlannerSpec.
 */
@Canonical
class PageCursor {
    int offset = 0
    String opaque

    /** TODO WS4. Null/blank cookie → first page. */
    static PageCursor decode(String cookie) {
        throw new UnsupportedOperationException('TODO WS4: PageCursor.decode')
    }

    /** TODO WS4. */
    String encode() {
        throw new UnsupportedOperationException('TODO WS4: PageCursor.encode')
    }

    /** Query parameters for this page. TODO WS4. */
    Map<String, Object> toQuery(int pageSize) {
        throw new UnsupportedOperationException('TODO WS4: PageCursor.toQuery')
    }

    /** Next cursor after {@code response}; null when the last page has been read. TODO WS4. */
    PageCursor next(HttpResponse response, int itemsOnPage, int pageSize) {
        throw new UnsupportedOperationException('TODO WS4: PageCursor.next')
    }
}
