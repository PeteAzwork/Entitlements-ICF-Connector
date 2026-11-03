package au.com.azwork.idm.graphentity.support

import au.com.azwork.idm.graphentity.http.EntityApi
import au.com.azwork.idm.graphentity.http.HttpResponse

import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * In-memory model of the remote graph entity service implementing the documented semantics (design §2):
 * <ul>
 *   <li>POST: 201; edges whose other endpoint does not exist are <b>silently dropped</b> (A7); duplicate → 409.</li>
 *   <li>GET: full document including every edge touching the entity, inbound ones as {@code SourceID}.</li>
 *   <li>PUT: full replacement; every edge touching the entity not in the payload is deleted. 404 if absent.</li>
 *   <li>DELETE: removes the entity and all edges touching it. 404 if absent.</li>
 *   <li>Search: {@code updatedAfter} (ISO instant) with offset/pageSize paging and a {@code totalCount}.</li>
 * </ul>
 * Also supports fault injection ({@link #failNextWith}) so operations can be tested against each §13 status.
 */
class FakeGraphService implements EntityApi {

    /** Canonical edge store: [sourceId, targetId, type]. */
    final Set<List<String>> edges = new LinkedHashSet<>()
    final Map<String, Map<String, Object>> entities = new LinkedHashMap<>()
    final Map<String, Instant> updatedAt = [:]
    final List<Map> requests = []
    String timestampParam = 'updatedAfter'
    private Instant clock = Instant.parse('2026-01-01T00:00:00Z')
    private final Deque<HttpResponse> injected = new ArrayDeque<>()

    void failNextWith(int status, Map body = null, Map<String, String> headers = [:]) {
        injected.add(new HttpResponse(status: status, body: body, rawBody: body ? groovy.json.JsonOutput.toJson(body) : null, headers: headers))
    }

    void tick() { clock = clock.plusSeconds(1) }

    private HttpResponse injectedOrNull(String op, Map detail) {
        requests << ([op: op] + detail)
        injected.isEmpty() ? null : injected.poll()
    }

    @Override
    HttpResponse get(String id) {
        def r = injectedOrNull('GET', [id: id]); if (r) return r
        if (!entities.containsKey(id)) return new HttpResponse(status: 404)
        new HttpResponse(status: 200, body: render(id))
    }

    @Override
    HttpResponse create(Map<String, Object> document) {
        def r = injectedOrNull('POST', [body: document]); if (r) return r
        String id = document.ID
        if (entities.containsKey(id)) return new HttpResponse(status: 409, body: [error: 'duplicate'])
        store(id, document, true)
        new HttpResponse(status: 201, body: render(id))
    }

    @Override
    HttpResponse replace(String id, Map<String, Object> document) {
        def r = injectedOrNull('PUT', [id: id, body: document]); if (r) return r
        if (!entities.containsKey(id)) return new HttpResponse(status: 404)
        edges.removeAll { it[0] == id || it[1] == id }
        store(id, document, false)
        new HttpResponse(status: 200, body: render(id))
    }

    @Override
    HttpResponse delete(String id) {
        def r = injectedOrNull('DELETE', [id: id]); if (r) return r
        if (!entities.containsKey(id)) return new HttpResponse(status: 404)
        entities.remove(id); updatedAt.remove(id)
        edges.removeAll { it[0] == id || it[1] == id }
        new HttpResponse(status: 204)
    }

    @Override
    HttpResponse search(Map<String, Object> query) {
        def r = injectedOrNull('SEARCH', [query: query]); if (r) return r
        Instant since = query[timestampParam] ? Instant.parse(query[timestampParam] as String) : Instant.EPOCH
        int offset = (query.offset ?: 0) as int
        int pageSize = (query.pageSize ?: 200) as int
        def ids = entities.keySet().findAll { updatedAt[it].isAfter(since) }.sort()
        def page = ids.drop(offset).take(pageSize).collect { render(it) }
        new HttpResponse(status: 200, body: [items: page, totalCount: ids.size(), offset: offset, pageSize: pageSize])
    }

    // ---- helpers ----

    private void store(String id, Map<String, Object> doc, boolean dropMissing) {
        entities[id] = [ID: id, BaseType: doc.BaseType, attributes: new LinkedHashMap(doc.attributes ?: [:]),
                        directArchetypes: new ArrayList(doc.directArchetypes ?: [])]
        tick(); updatedAt[id] = clock
        (doc.Relationships ?: []).each { Map e ->
            String src = e.SourceID ? e.SourceID : id
            String tgt = e.TargetID ? e.TargetID : id
            String other = e.SourceID ? src : tgt
            if (!entities.containsKey(other)) {
                if (dropMissing) return  // A7: silently dropped on POST
                return                   // service also cannot link to a missing entity on PUT
            }
            edges << [src, tgt, e.type as String]
        }
    }

    Map<String, Object> render(String id) {
        def e = entities[id]
        def rels = edges.findAll { it[0] == id || it[1] == id }.collect {
            it[0] == id ? [TargetID: it[1], type: it[2]] : [SourceID: it[0], type: it[2]]
        }
        [ID: id, BaseType: e.BaseType, attributes: new LinkedHashMap(e.attributes), Relationships: rels,
         directArchetypes: new ArrayList(e.directArchetypes),
         lastUpdated: DateTimeFormatter.ISO_INSTANT.format(updatedAt[id])]
    }

    /** Convenience for seeding state directly (bypasses drop semantics). */
    void seed(Map<String, Object> doc) {
        entities[doc.ID as String] = [ID: doc.ID, BaseType: doc.BaseType, attributes: new LinkedHashMap(doc.attributes ?: [:]),
                                       directArchetypes: new ArrayList(doc.directArchetypes ?: [])]
        tick(); updatedAt[doc.ID as String] = clock
    }

    void seedEdge(String sourceId, String targetId, String type) { edges << [sourceId, targetId, type] }

    List<Map> requestsOf(String op) { requests.findAll { it.op == op } }
}
