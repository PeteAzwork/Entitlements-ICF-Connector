package au.com.azwork.idm.graphentity.support

import au.com.azwork.idm.graphentity.http.EntityApi
import au.com.azwork.idm.graphentity.http.HttpResponse

import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * In-memory model of the IDP Entitlements API as the connector uses it (service contract §5; design §2).
 *
 * <p><b>Documented behaviour</b>, from the OpenAPI definition:
 * <ul>
 *   <li>Create ({@code POST /admin/entities}) takes the write model, EntityDTO: {@code id}, {@code baseType},
 *       {@code attributes} as string → string, {@code relationships} as {@code {sourceID, targetID, type}},
 *       {@code directArchetypes}. 200 with no body; 400 for an invalid body. By design, {@code attributes.ID} must
 *       equal {@code id}, or the write gets a 500 with no body (C8); reads never return {@code ID}.</li>
 *   <li>Read ({@code GET /admin/entities/{id}}) returns the read model, Entity: {@code id}, {@code baseType},
 *       {@code Attributes} as name → {@code {name, type, value}}, {@code compoundAttributes}, {@code relationships}
 *       as {@code {source, target, type, sourceType}}, {@code lastEvaluated}, {@code reconciliationStarted}. 404 when
 *       missing.</li>
 *   <li>Update ({@code PUT /admin/entities/{id}}) takes the write model; 404 when missing.</li>
 *   <li>Delete ({@code DELETE /admin/entities/{id}}) returns the deleted Entity; 404 when missing.</li>
 *   <li>Search ({@code POST /admin/entities/search}) takes {@code objectClass}, a {@code filter} tree,
 *       {@code pageSize} (default 100) and {@code pagedResultsCookie}; returns {@code results} (search items:
 *       {@code uid}, {@code name}, {@code baseType} as a name, {@code attributes}, {@code isArchetypes},
 *       {@code directArchetypes}, {@code lastEvaluated}; no relationships. By design, {@code isArchetypes} lists the
 *       directly assigned archetypes plus any assigned by policy, and {@code directArchetypes} is empty (C9)), {@code pagedResultsCookie} (null when
 *       there are no more), {@code totalResults} and {@code hasMore}. Sorted by ID.</li>
 *   <li>Manifests ({@code GET /admin/manifests}) list the archetype definitions; 404 when there are none.</li>
 *   <li>Errors are RFC 7807 problem details, {@code application/problem+json}.</li>
 * </ul>
 *
 * <p><b>Assumed behaviour</b>, still to be confirmed (service contract §2, §6; WS0.15). Change these together with
 * the specs that rely on them when the answers arrive:
 * <ul>
 *   <li>A create silently drops a relationship whose other end does not exist (design A7).</li>
 *   <li>A relationship that does not name the entity being written as source or target is a 400.</li>
 *   <li>An update replaces every relationship touching the entity, in both directions (P15, Q07.8, design A2); an
 *       empty list purges them all, as the service owner states. (The real service did not purge on an empty list in
 *       run 20261121-215954; the owner treats that as a bug to be fixed, C17.)</li>
 *   <li>An update replaces {@code directArchetypes} in full (P22, observed) and <b>merges</b> {@code attributes}: an
 *       attribute the update omits is kept, and one written as {@code ""} is stored empty (P20, observed, C17).</li>
 *   <li>A duplicate create is a 409 (Q04.1; the definition documents none, and the probe saw 200).</li>
 *   <li>Base type 4 is Subject and 2 is Resource (P18; the definition's create samples say the reverse).</li>
 *   <li>Filter operations are sent by name; EQUALS, CONTAINS, STARTSWITH, AND and OR are supported, matched on
 *       attributes, and on the entity ID when the left side is {@code "ID"}. Other operations are a 400.</li>
 *   <li>Attribute values must be strings; any other JSON type is a 400.</li>
 * </ul>
 * Also supports fault injection ({@link #failNextWith}) so operations can be tested against each §13 status.
 *
 * <p>Helpers are {@code protected}, not {@code private}: closures in this class dispatch on the runtime object, so a
 * private method called from a closure is invisible when a spec subclasses the fake to model a real behaviour.
 */
class FakeGraphService implements EntityApi {

    static final Map<Integer, String> BASE_TYPE_NAMES = [4: 'Subject', 2: 'Resource']
    static final Set<String> FILTER_OPERATIONS = ['EQUALS', 'CONTAINS', 'STARTSWITH', 'AND', 'OR'] as Set
    static final int DEFAULT_PAGE_SIZE = 100

    /** Relationship store: [sourceId, targetId, type]. */
    final Set<List<String>> edges = new LinkedHashSet<>()
    /** id → [id, baseType (int), attributes (string → string), directArchetypes, isArchetypes]. */
    final Map<String, Map<String, Object>> entities = new LinkedHashMap<>()
    final Map<String, Instant> lastEvaluated = [:]
    /** Manifests as the service returns them; an empty list makes GET /admin/manifests a 404. */
    final List<Map<String, Object>> manifests = []
    final List<Map> requests = []
    protected Instant clock = Instant.parse('2026-01-01T00:00:00Z')
    protected final Deque<HttpResponse> injected = new ArrayDeque<>()

    // ------------------------------------------------------------------ test control

    void failNextWith(int status, Map body = null, Map<String, String> headers = [:]) {
        injected.add(new HttpResponse(status: status, body: body, rawBody: body ? groovy.json.JsonOutput.toJson(body) : null, headers: headers))
    }

    void tick() { clock = clock.plusSeconds(1) }

    protected HttpResponse injectedOrNull(String op, Map detail) {
        requests << ([op: op] + detail)
        injected.isEmpty() ? null : injected.poll()
    }

    List<Map> requestsOf(String op) { requests.findAll { it.op == op } }

    /** Seeds an entity directly from a write-model map (bypasses validation and drop semantics). */
    void seed(Map<String, Object> doc) {
        String id = doc.id as String
        entities[id] = [id: id, baseType: doc.baseType as Integer, attributes: withoutId(doc.attributes),
                        directArchetypes: new ArrayList(doc.directArchetypes ?: []), isArchetypes: new ArrayList(doc.isArchetypes ?: [])]
        tick(); lastEvaluated[id] = clock
    }

    void seedEdge(String sourceId, String targetId, String type) { edges << [sourceId, targetId, type] }

    /** Adds a manifest defining the given subject and resource archetype names. */
    void seedManifest(String name, List<String> subjectArchetypes, List<String> resourceArchetypes) {
        manifests << [name: name, subjectArchetypes: subjectArchetypes.collect { [name: it] }, resourceArchetypes: resourceArchetypes.collect { [name: it] }]
    }

    // ------------------------------------------------------------------ EntityApi

    @Override
    HttpResponse get(String id) {
        def r = injectedOrNull('GET', [id: id]); if (r) return r
        if (!entities.containsKey(id)) return problem(404, 'Not Found')
        new HttpResponse(status: 200, body: renderEntity(id), headers: json())
    }

    @Override
    HttpResponse create(Map<String, Object> document) {
        def r = injectedOrNull('POST', [body: document]); if (r) return r
        if (!idMirrored(document)) return new HttpResponse(status: 500)   // by design (C8)
        def invalid = validate(document, null)
        if (invalid) return problem(400, invalid)
        String id = document.id
        if (entities.containsKey(id)) return problem(409, 'Conflict')   // assumption, Q04.1
        store(id, document)
        new HttpResponse(status: 200)
    }

    @Override
    HttpResponse replace(String id, Map<String, Object> document) {
        def r = injectedOrNull('PUT', [id: id, body: document]); if (r) return r
        if (!idMirrored(document)) return new HttpResponse(status: 500)   // by design (C8)
        def invalid = validate(document, id)
        if (invalid) return problem(400, invalid)
        if (!entities.containsKey(id)) return problem(404, 'Not Found')
        edges.removeAll { it[0] == id || it[1] == id }   // assumption: both directions (Q07.8)
        store(id, document)
        new HttpResponse(status: 200)
    }

    @Override
    HttpResponse delete(String id) {
        def r = injectedOrNull('DELETE', [id: id]); if (r) return r
        if (!entities.containsKey(id)) return problem(404, 'Not Found')
        def deleted = renderEntity(id)
        entities.remove(id); lastEvaluated.remove(id)
        edges.removeAll { it[0] == id || it[1] == id }
        new HttpResponse(status: 200, body: deleted, headers: json())
    }

    @Override
    HttpResponse search(Map<String, Object> query) {
        def r = injectedOrNull('SEARCH', [query: query]); if (r) return r
        Integer objectClass = null
        if (query.objectClass != null) {
            objectClass = baseTypeOf(query.objectClass)
            if (objectClass == null) return problem(400, "Unknown objectClass ${query.objectClass}")
        }
        Map filter = query.filter instanceof Map ? query.filter as Map : null
        if (query.filter != null && filter == null) return problem(400, 'filter must be an object')
        String filterError = filter ? checkFilter(filter) : null
        if (filterError) return problem(400, filterError)
        int pageSize
        int offset
        try {
            pageSize = query.pageSize == null ? DEFAULT_PAGE_SIZE : (query.pageSize as int)
            offset = query.pagedResultsCookie == null ? 0 : Integer.parseInt(query.pagedResultsCookie as String)
        } catch (NumberFormatException e) {
            return problem(400, 'Invalid paging')
        }
        if (pageSize < 1 || offset < 0) return problem(400, 'Invalid paging')
        def ids = entities.keySet().findAll { id ->
            (objectClass == null || entities[id].baseType == objectClass) && (filter == null || matches(filter, id))
        }.sort()
        def page = ids.drop(offset).take(pageSize).collect { renderSearchItem(it) }
        int next = offset + page.size()
        boolean more = next < ids.size()
        new HttpResponse(status: 200, headers: json(), body: [results: page, pagedResultsCookie: more ? (next as String) : null,
                                                              totalResults: ids.size(), hasMore: more])
    }

    @Override
    HttpResponse manifests() {
        def r = injectedOrNull('MANIFESTS', [:]); if (r) return r
        manifests ? new HttpResponse(status: 200, body: manifests.collect { new LinkedHashMap(it) }, headers: json()) : problem(404, 'Not Found')
    }

    // ------------------------------------------------------------------ relationship endpoints (HTTP only)

    /** POST /admin/entities/{id}/relationships with one {sourceID, targetID, type}. */
    HttpResponse addRelationship(String id, Map<String, Object> rel) {
        def r = injectedOrNull('ADD_RELATIONSHIP', [id: id, body: rel]); if (r) return r
        def invalid = validateEdge(rel, id)
        if (invalid) return problem(400, invalid)
        if (!entities.containsKey(id)) return problem(404, 'Not Found')
        String other = rel.sourceID == id ? rel.targetID : rel.sourceID
        if (entities.containsKey(other)) edges << [rel.sourceID as String, rel.targetID as String, rel.type as String]   // assumption: A7 drop
        new HttpResponse(status: 200)
    }

    /** DELETE /admin/entities/{source}/relationships/{target}[/{type}]. */
    HttpResponse deleteRelationship(String sourceId, String targetId, String type) {
        def r = injectedOrNull('DELETE_RELATIONSHIP', [source: sourceId, target: targetId, type: type]); if (r) return r
        edges.removeAll { it[0] == sourceId && it[1] == targetId && (type == null || it[2] == type) }
        new HttpResponse(status: 200)
    }

    // ------------------------------------------------------------------ rendering

    /** The read model, Entity. */
    Map<String, Object> renderEntity(String id) {
        def e = entities[id]
        [id                   : id,
         lastEvaluated        : DateTimeFormatter.ISO_INSTANT.format(lastEvaluated[id]),
         reconciliationStarted: null,
         baseType             : e.baseType,
         Attributes           : (e.attributes as Map).collectEntries { k, v -> [(k): [name: k, type: 'string', value: v]] },
         compoundAttributes   : [:],
         relationships        : edges.findAll { it[0] == id || it[1] == id }.collect {
             [source: it[0], target: it[1], type: it[2], sourceType: baseTypeName(entities[it[0]]?.baseType)]
         }]
    }

    /** One search result item. */
    Map<String, Object> renderSearchItem(String id) {
        def e = entities[id]
        [uid             : id,
         name            : id,
         baseType        : baseTypeName(e.baseType),
         attributes      : new LinkedHashMap(e.attributes as Map),
         isArchetypes    : ((e.directArchetypes as List) + (e.isArchetypes as List)).unique(),   // C9, by design
         directArchetypes: [],
         lastEvaluated   : DateTimeFormatter.ISO_INSTANT.format(lastEvaluated[id])]
    }

    // ------------------------------------------------------------------ helpers

    static String baseTypeName(Object baseType) {
        baseType == null ? null : (BASE_TYPE_NAMES[baseType as Integer] ?: baseType as String)
    }

    /** A number, numeric string or name → the numeric base type, or null when unknown. */
    static Integer baseTypeOf(Object v) {
        if (v instanceof Number) return (v as Number).intValue()
        String s = v as String
        if (s ==~ /\d+/) return Integer.parseInt(s)
        BASE_TYPE_NAMES.find { k, name -> name.equalsIgnoreCase(s) }?.key
    }

    /** The stored attributes: attributes.ID only exists for C8 and is never returned. */
    protected static Map withoutId(Object attrs) {
        new LinkedHashMap((attrs ?: [:]) as Map).findAll { k, v -> k != 'ID' } as LinkedHashMap
    }

    protected static Map<String, String> json() { ['Content-Type': 'application/json; charset=utf-8'] }

    /**
     * RFC 7807 problem details. A 400 has the service's shape (recorded in run 20261121-215954): a generic title and
     * an {@code errors} map from field to messages; other statuses carry the given title.
     */
    protected static HttpResponse problem(int status, String title) {
        def body = status == 400 ?
            [type: 'https://tools.ietf.org/html/rfc9110#section-15.5.1', title: 'One or more validation errors occurred.', status: 400,
             errors: [entity: [title]], traceId: '00-fake-00'] :
            [type: "https://tools.ietf.org/html/rfc9110#section-15.5.${status == 404 ? 5 : 10}".toString(), title: title, status: status, traceId: '00-fake-00']
        new HttpResponse(status: status, body: body, rawBody: groovy.json.JsonOutput.toJson(body),
            headers: ['Content-Type': 'application/problem+json; charset=utf-8'])
    }

    /** C8: the service requires attributes.ID equal to id. Checked only when there is an id to compare. */
    protected static boolean idMirrored(Map<String, Object> doc) {
        !(doc.id instanceof String) || (doc.attributes instanceof Map && (doc.attributes as Map).ID == doc.id)
    }

    /** Returns an error message for an invalid write-model document, or null. */
    protected String validate(Map<String, Object> doc, String pathId) {
        if (!(doc.id instanceof String) || !(doc.id as String).trim()) return 'id is required'
        if (pathId != null && doc.id != pathId) return 'id does not match the path'
        if (baseTypeOf(doc.baseType) == null) return 'baseType is required'
        def attrs = doc.attributes ?: [:]
        if (!(attrs instanceof Map)) return 'attributes must be an object'
        def bad = (attrs as Map).find { k, v -> !(v instanceof String) }
        if (bad) return "attributes.${bad.key} must be a string"
        if (doc.directArchetypes != null && !(doc.directArchetypes instanceof List)) return 'directArchetypes must be an array'
        if (doc.relationships != null && !(doc.relationships instanceof List)) return 'relationships must be an array'
        for (rel in (doc.relationships ?: [])) {
            def e = validateEdge(rel instanceof Map ? rel as Map : null, doc.id as String)
            if (e) return e
        }
        null
    }

    protected static String validateEdge(Map rel, String entityId) {
        if (rel == null) return 'relationship must be an object'
        if (!(['sourceID', 'targetID', 'type'].every { rel[it] instanceof String && (rel[it] as String).trim() })) return 'relationship needs sourceID, targetID and type'
        if (rel.sourceID != entityId && rel.targetID != entityId) return 'relationship must involve the entity'   // assumption
        null
    }

    protected void store(String id, Map<String, Object> doc) {
        def existing = entities[id]
        Map attrs = existing ? (existing.attributes as Map) + withoutId(doc.attributes) : withoutId(doc.attributes)   // merged (C17)
        entities[id] = [id: id, baseType: baseTypeOf(doc.baseType), attributes: attrs,
                        directArchetypes: new ArrayList(doc.directArchetypes ?: []),
                        isArchetypes: new ArrayList(existing?.isArchetypes ?: [])]
        tick(); lastEvaluated[id] = clock
        (doc.relationships ?: []).each { Map e ->
            String other = e.sourceID == id ? e.targetID : e.sourceID
            if (other == id || entities.containsKey(other)) edges << [e.sourceID as String, e.targetID as String, e.type as String]
            // else: silently dropped (assumption, design A7)
        }
    }

    protected static String checkFilter(Map f) {
        String op = (f.operation as String)?.toUpperCase()
        if (!(op in FILTER_OPERATIONS)) return "Unsupported filter operation ${f.operation}"
        if (op in ['AND', 'OR']) {
            if (!(f.left instanceof Map) || !(f.right instanceof Map)) return "${op} needs two filter operands"
            return checkFilter(f.left as Map) ?: checkFilter(f.right as Map)
        }
        if (!(f.left instanceof String)) return 'left must be an attribute name'
        null
    }

    protected boolean matches(Map f, String id) {
        String op = (f.operation as String).toUpperCase()
        boolean result
        if (op == 'AND') result = matches(f.left as Map, id) && matches(f.right as Map, id)
        else if (op == 'OR') result = matches(f.left as Map, id) || matches(f.right as Map, id)
        else {
            String left = f.left as String
            String actual = left.equalsIgnoreCase('ID') ? id : (entities[id].attributes as Map)[left] as String
            String expected = f.right as String
            result = actual != null && (op == 'EQUALS' ? actual == expected : op == 'CONTAINS' ? actual.contains(expected) : actual.startsWith(expected))
        }
        f.not == true ? !result : result
    }
}
