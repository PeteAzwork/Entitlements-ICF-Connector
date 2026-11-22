package au.com.azwork.idm.entitlementsaccelerator.search

import au.com.azwork.idm.entitlementsaccelerator.http.HttpResponse
import groovy.json.JsonException
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Canonical

import java.nio.charset.StandardCharsets

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
    /**
     * Results the service returned before this page, so a paged search can report how many remain against
     * {@code totalResults}; the service's cookie is opaque, so the count travels in the ICF cookie as {@code n}.
     */
    int consumed = 0

    /** Null/blank cookie → first page. Anything undecodable → IllegalArgumentException. */
    static PageCursor decode(String cookie) {
        if (cookie == null || !cookie.trim()) return new PageCursor()
        Object decoded
        try {
            String json = new String(Base64.urlDecoder.decode(cookie.trim()), StandardCharsets.UTF_8)
            decoded = new JsonSlurper().parseText(json)
        } catch (IllegalArgumentException | JsonException e) {
            throw new IllegalArgumentException('The paged-results cookie is not one this connector issued; start the search again from the first page', e)
        }
        Object c = decoded instanceof Map ? (decoded as Map).c : null
        if (!(c instanceof CharSequence) || !c.toString()) {
            throw new IllegalArgumentException('The paged-results cookie carries no position; start the search again from the first page')
        }
        Object n = (decoded as Map).n
        if (n != null && !(n instanceof Number && (n as Number).intValue() >= 0)) {
            throw new IllegalArgumentException('The paged-results cookie carries an invalid position; start the search again from the first page')
        }
        new PageCursor(serviceCookie: c.toString(), consumed: n == null ? 0 : (n as Number).intValue())
    }

    /** URL-safe base64 of {@code {"c": <service cookie>, "n": <results before it>}} ({@code n} omitted when 0). */
    String encode() {
        if (serviceCookie == null) throw new IllegalStateException('The first page has no cookie to encode')
        Map<String, Object> m = [c: serviceCookie] as Map<String, Object>
        if (consumed > 0) m.n = consumed
        Base64.urlEncoder.encodeToString(JsonOutput.toJson(m).getBytes(StandardCharsets.UTF_8))
    }

    /** Request-body fields for this page: {@code pageSize}, plus {@code pagedResultsCookie} after the first page. */
    Map<String, Object> toQuery(int pageSize) {
        Map<String, Object> q = new LinkedHashMap<>()
        q.pageSize = pageSize
        if (serviceCookie != null) q.pagedResultsCookie = serviceCookie
        q
    }

    /**
     * Cursor for the next page from a search response; null when {@code pagedResultsCookie} is null, when
     * {@code hasMore} is false, or when the page was empty (a cookie on an empty page would loop forever).
     */
    static PageCursor next(HttpResponse response) {
        Map body = response?.body instanceof Map ? response.body as Map : [:]
        Object cookie = body.pagedResultsCookie
        if (cookie == null || !cookie.toString()) return null
        if (body.hasMore == false) return null
        if (!(body.results instanceof Collection) || (body.results as Collection).isEmpty()) return null
        new PageCursor(serviceCookie: cookie.toString())
    }

    /** {@code totalResults} from a search response, or -1 when absent or unknown. */
    static int totalResults(HttpResponse response) {
        Object t = response?.body instanceof Map ? (response.body as Map).totalResults : null
        t instanceof Number && (t as Number).intValue() >= 0 ? (t as Number).intValue() : -1
    }
}
