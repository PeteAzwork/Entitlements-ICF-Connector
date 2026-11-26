package idm.entitlementsaccelerator.loadtest

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reads the service directly, to check what IDM's writes produced (docs/load-testing.md §4). Holds nothing per call,
 * so 100,000 reads cost no memory. Counts every status it sees, 429s and 5xx included, for the report.
 *
 * <p>What the service allows, from docs/service-contract.md: search is {@code POST /admin/entities/search} with a filter
 * tree; an {@code EQUALS} filter on {@code ID} works (the connector's by-ID fallback, P21); GET by ID is unreliable
 * (C1), so relationships, which only the read model carries, may be unreadable. A {@code STARTSWITH} filter on
 * {@code ID} is not proven on the real service: if it is refused (400), the client pages through everything and
 * filters by prefix itself, and says so.
 */
class ServiceClient {
    static final String SEARCH = '/admin/entities/search'
    static final String ENTITIES = '/admin/entities/'

    final String base
    final Map<Integer, AtomicInteger> statuses = new ConcurrentHashMap<>()
    /** Set when the service refused a STARTSWITH filter on ID, and prefix searches page through everything instead. */
    volatile boolean prefixFilterRefused
    private final String header
    private final String key
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).proxy(HttpClient.Builder.NO_PROXY).build()

    ServiceClient(String base, String header, char[] key) {
        this.base = base
        this.header = header
        this.key = new String(key)
    }

    /** Every entity whose ID starts with {@code prefix}, as search items ({@code uid}, {@code attributes}, …). */
    List<Map> findByPrefix(String prefix) {
        if (!prefixFilterRefused) {
            try {
                return pages([operation: 'STARTSWITH', left: 'ID', right: prefix]) { true }
            } catch (ServiceException e) {
                if (e.status != 400) throw e
                prefixFilterRefused = true
            }
        }
        pages(null) { Map item -> idOf(item)?.startsWith(prefix) }
    }

    /** One entity by an EQUALS filter on its ID (proven on the real service): a search item, or null. */
    Map findById(String id) {
        Map page = search([filter: [operation: 'EQUALS', left: 'ID', right: id], pageSize: 1])
        (page.results as List<Map>)?.find { idOf(it) == id }
    }

    /** The read model by GET: [status, body]. 500 is common (C1); the caller treats it as "cannot tell". */
    Map read(String id) {
        HttpResponse<String> r = send('GET', ENTITIES + enc(id), null)
        [status: r.statusCode(), body: r.statusCode() == 200 && r.body() ? new JsonSlurper().parseText(r.body()) : null]
    }

    /** Deletes one entity directly, for the sweep after cleanup: the status. */
    int delete(String id) { send('DELETE', ENTITIES + enc(id), null).statusCode() }

    /** Reachable with this key: a one-item search answers 2xx. */
    boolean reachable() {
        search([pageSize: 1])
        true
    }

    Map<String, Integer> statusCounts() {
        new TreeMap<String, Integer>(statuses.collectEntries { k, v -> [(k.toString()): v.get()] } as Map<String, Integer>)
    }

    static String idOf(Map item) { (item.uid ?: item.id ?: (item.attributes as Map)?.ID) as String }

    private List<Map> pages(Map filter, Closure<Boolean> keep) {
        List<Map> out = []
        String cookie = null
        while (true) {
            Map body = [pageSize: 1000] as Map
            if (filter) body.filter = filter
            if (cookie) body.pagedResultsCookie = cookie
            Map page = search(body)
            (page.results as List<Map> ?: []).each { if (keep(it)) out << it }
            cookie = page.pagedResultsCookie as String
            if (!cookie) return out
        }
    }

    /** One search; a 429 is waited out (Retry-After, at most 30 s, five times) and counted, so checks don't fail on it. */
    Map search(Map body) {
        for (int attempt = 0; ; attempt++) {
            HttpResponse<String> r = send('POST', SEARCH, body)
            if (r.statusCode() == 429 && attempt < 5) {
                String retryAfter = r.headers().firstValue('Retry-After').orElse('1')
                long wait = retryAfter ==~ /\d+/ ? Math.min(30L, retryAfter as long) : 1L
                Thread.sleep(Math.max(1L, wait) * 1000)
                continue
            }
            if (r.statusCode() >= 300) throw new ServiceException(r.statusCode())
            return r.body() ? new JsonSlurper().parseText(r.body()) as Map : [:]
        }
    }

    private HttpResponse<String> send(String method, String path, Object body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(60))
            .header(header, key).header('Accept', 'application/json')
        if (body != null) b.header('Content-Type', 'application/json')
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JsonOutput.toJson(body)))
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
        statuses.computeIfAbsent(r.statusCode()) { new AtomicInteger() }.incrementAndGet()
        r
    }

    static String enc(String id) { URLEncoder.encode(id, 'UTF-8').replace('+', '%20') }

    /** A non-2xx answer; carries the status only, never the body, which could echo request data. */
    static class ServiceException extends RuntimeException {
        final int status
        ServiceException(int status) { super("the service answered HTTP ${status}".toString()); this.status = status }
    }
}
