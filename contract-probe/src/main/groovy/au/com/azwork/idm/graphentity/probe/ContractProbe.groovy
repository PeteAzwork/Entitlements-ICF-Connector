package au.com.azwork.idm.graphentity.probe

import java.time.Duration

/**
 * WS0 contract probe. Observes the graph entity service and answers as many of the delivery plan's WS0
 * questions as can be answered by observation, recording every exchange as evidence.
 *
 * <p>Realigned in WS0.15 to the service's OpenAPI definition (service contract §5) and decisions P17–P22:
 * writes send the write model (EntityDTO: {@code id}, numeric {@code baseType}, string {@code attributes},
 * {@code relationships} as {@code {sourceID, targetID, type}}, {@code directArchetypes}); reads expect the read
 * model on GET and search items from the search. GET by ID failed throughout the first real run (C1), so every
 * read-back falls back to a search with an {@code EQUALS} filter on the ID when GET fails. Relationships are
 * only in the read model, so relationship checks cannot fall back and say so.
 *
 * <p>Read-only by default: only GET requests and search POSTs (to the search endpoint, which reads) are
 * issued. With {@code allowWrites}, it also creates, replaces and deletes entities whose ID starts with a
 * per-run prefix ({@code ws0-probe-<runId>-}), and removes them all at the end, including after a failure.
 * It never writes to any other entity.
 *
 * <p>Results are left in {@link #findings} and {@link #contract}; {@link ProbeReport} writes them out.
 */
class ContractProbe {

    static final String EDGE_TYPE = 'Ws0ProbeEdge'
    /** Base types as decision P18 numbers them. Check WS0.15 confirms or refutes this numbering. */
    static final int SUBJECT = 4
    static final int RESOURCE = 2

    static final String SEARCH_PATH = '/admin/entities/search'
    static final String CREATE_PATH = '/admin/entities'
    static final String MANIFESTS_PATH = '/admin/manifests'
    /** Paths a search POST must never go to: a POST there creates an entity. */
    static final List<String> CREATE_PATHS = ['/admin/entities', '/admin/entities/']

    /** Documented top-level fields of a search item and of the read model (service contract §5). */
    static final List<String> SEARCH_ITEM_KEYS = ['uid', 'name', 'baseType', 'attributes', 'isArchetypes', 'directArchetypes', 'lastEvaluated']
    static final List<String> READ_MODEL_KEYS = ['id', 'lastEvaluated', 'reconciliationStarted', 'baseType', 'Attributes', 'compoundAttributes', 'relationships']
    /** Candidate names for filtering on the entity ID; the definition's default sort key is "ID" (P21). */
    static final List<String> ID_FILTER_FIELDS = ['ID', 'id', 'uid']
    /** objectClass values tried read-only to learn the base-type numbering (P18, C6). */
    static final List<Object> OBJECT_CLASS_CANDIDATES = [SUBJECT, RESOURCE, 'Subject', 'Resource']

    /**
     * Create-body variants tried one at a time (check {@code create.shape}). The first is the body as designed: the
     * EntityDTO with {@code attributes.ID} equal to {@code id}, which the service requires (C8, confirmed by the
     * service owner). The others each change one thing, so a change in the service shows up as a changed answer.
     * The first accepted variant is used for every later write in the run.
     */
    static final List<Map<String, Object>> CREATE_VARIANTS = [
        [name: 'EntityDTO with attributes.ID (C8)'],
        [name: 'EntityDTO without attributes.ID', dropId: true],
        [name: 'EntityDTO with attributes.ID and a direct archetype', archetype: true],
        [name: 'EntityDTO with attributes.ID, without empty arrays', omitEmpty: true],
        [name: 'EntityDTO with attributes.ID, path with a trailing slash', trailingSlash: true],
        [name: 'design casing (ID, BaseType, Relationships)', legacyKeys: true]
    ].asImmutable() as List<Map<String, Object>>
    static final Map<String, String> LEGACY_KEYS = [id: 'ID', baseType: 'BaseType', relationships: 'Relationships']
    /** The archetype the first run stamped on its entities, which the service accepted. */
    static final String PROBE_ARCHETYPE = 'Ws0Probe'

    static final List<String> ITEM_KEYS = ['results', 'items', 'data', 'entities', 'resources', 'content', 'value', 'result']
    static final List<String> TOTAL_KEYS = ['totalResults', 'totalCount', 'total', 'count', 'total_count', 'resultCount']
    static final List<String> CURSOR_KEYS = ['pagedResultsCookie', '_pagedResultsCookie', 'nextCursor', 'next', 'cursor', 'continuationToken', 'nextPageToken', 'pageToken']

    final ProbeOptions opts
    final ProbeHttp http
    final Scrubber scrubber
    final List<Finding> findings = []
    /** Every probe ID a write was attempted for, so cleanup can remove it whatever the outcome. */
    final Set<String> touched = new LinkedHashSet<>()
    final List<String> cleanupProblems = []
    /** How each probe entity's removal was confirmed: "GET 404" or "search". */
    final Map<String, String> cleanupConfirmedBy = [:]
    /** Probe entities deleted with a 2xx whose removal no read could prove (GET fails and search is not trusted). */
    final List<String> cleanupUnconfirmed = []
    /** Probe IDs whose create or update was accepted. Only these need their removal proved. */
    final Set<String> written = new LinkedHashSet<>()
    /** The create variant adopted by {@code create.shape}; the documented EntityDTO until then. */
    Map<String, Object> createShape = CREATE_VARIANTS[0]
    /** Relationship form for writes: 'P17' ({sourceID, targetID, type}) or 'design' ({TargetID|SourceID, type}). */
    String relationshipForm = 'P17'
    /** What was discovered about the API, for the "suggested values" part of the report. */
    final Map<String, Object> contract = [:]

    private boolean aborted = false
    /** A foreign entity's ID for the ID-filter check. Kept out of {@link #contract}, which is written to disk. */
    private String searchSampleId

    ContractProbe(ProbeOptions opts, ProbeHttp http = null) {
        this.opts = opts
        this.http = http ?: new ProbeHttp(opts.baseUrl, opts.apiKeyHeader, opts.apiKey, Duration.ofSeconds(opts.timeoutSeconds))
        this.scrubber = new Scrubber(opts.prefix, [opts.searchPath ?: SEARCH_PATH], ProbeOptions.NAMESPACE)
    }

    // ------------------------------------------------------------------ orchestration

    ContractProbe run() {
        try {
            step('WS0.10') { checkAuth() }
            boolean writable = false
            if (opts.allowWrites) {
                step('WS0.15') { writable = checkCreateShape() }
                if (writable) step('WS0.03') { seed() }
            }
            step('WS0.02') { checkSearchEndpoint() }
            step('WS0.15') { checkObjectClassNumbering() }
            step('WS0.02') { checkIdFilter() }
            step('WS0.03') { checkPaging() }
            step('WS0.06') { checkDocumentFields() }
            step('WS0.08') { checkTypeDiscrimination() }
            step('WS0.15') { checkManifests() }
            step('WS0.05') { checkErrorsReadOnly() }
            step('WS0.15') { checkLeftovers() }
            if (opts.allowWrites && writable) {
                step('WS0.15') { checkRelationshipShape() }
                step('WS0.02') { checkSearchReflectsDelete() }
                step('WS0.15') { checkBaseTypeOnCreate() }
                step('WS0.07') { checkEdgeSemantics() }
                step('WS0.15') { checkAttributeOmission() }
                step('WS0.15') { checkArchetypeReplacement() }
                step('WS0.05') { checkErrorsWrite() }
                step('WS0.09') { checkIds() }
                step('WS0.03') { checkPagingStability() }
            } else {
                String why = opts.allowWrites ? 'Not tested: no create variant was accepted (see `create.shape`).' : null
                if (!opts.allowWrites) {
                    notTested('WS0.15', 'create.shape', 'Which create body does the service accept?')
                }
                notTested('WS0.15', 'create.relationshipShape', 'Which relationship form does a create accept (P17)?', why)
                notTested('WS0.02', 'search.reflectsDelete', 'Does search stop returning an entity once it is deleted?', why)
                notTested('WS0.15', 'baseType.create', 'Is an entity created with base type 4 a Subject (P18)?', why)
                notTested('WS0.02', 'search.createdVisible', 'Is a newly created entity found by search (C5)?', why)
                notTested('WS0.04', 'create.duplicate', 'What does a duplicate POST return?', why)
                notTested('WS0.07', 'edges', 'Relationship semantics on POST, PUT, GET and DELETE, including Q07.8', why)
                notTested('WS0.15', 'attributes.omission', 'Does an update that omits an attribute remove it (P20)?', why)
                notTested('WS0.15', 'archetypes.replace', 'Does an update replace directArchetypes in full (P22)?', why)
                notTested('WS0.09', 'ids', 'ID characters, length and case sensitivity', why)
                notTested('WS0.03', 'paging.updateDuringEnumeration', 'Can an entity updated mid-enumeration be skipped or returned twice?', why)
            }
            http.currentCheck = 'WS0.10'
            checkLimitHeaders()
        } finally {
            if (opts.allowWrites) cleanup()
        }
        this
    }

    private void step(String task, Closure body) {
        if (aborted) return
        http.currentCheck = task
        int firstExchange = http.exchanges.size() + 1
        int firstFinding = findings.size()
        try {
            body()
        } catch (Exception e) {
            add(task, "${task}.unexpected", 'The check itself failed', e.toString(), 'error', 'A probe defect or an unexpected response shape. See exchanges.jsonl around this check.')
        } finally {
            // Findings that summarise many requests cite every exchange the check made.
            List<Integer> range = (firstExchange..http.exchanges.size()).findAll { it >= firstExchange } as List<Integer>
            findings.drop(firstFinding).findAll { it.evidence.empty && it.confidence != 'not-tested' }.each { it.evidence = range }
        }
    }

    private Finding add(String task, String key, String question, String answer, String confidence, String detail = null, List<Exchange> evidence = []) {
        def f = new Finding(task: task, key: key, question: question, answer: answer, confidence: confidence, detail: detail,
            evidence: evidence.findAll { it != null }*.n)
        findings << f
        f
    }

    private void notTested(String task, String key, String question, String why = null) {
        add(task, key, question, why ?: 'Not tested: needs --allow-writes against a test instance.', 'not-tested')
    }

    // ------------------------------------------------------------------ helpers: documents

    String entityPath(String id) { '/admin/entities/' + ProbeHttp.encodeSegment(id) }

    String pid(String suffix) { opts.prefix + suffix }

    /** A write model (EntityDTO) for a probe entity: string attributes (P20) and attributes.ID (C8). */
    static Map probeDoc(String id, List<Map> rels = [], Map<String, String> attrs = [:], int baseType = RESOURCE, List<String> archetypes = []) {
        [id: id, baseType: baseType, attributes: [ID: id, probe: 'ws0'] + attrs, relationships: rels, directArchetypes: archetypes]
    }

    /** One relationship in the write shape (P17). */
    static Map edge(String source, String target) { [sourceID: source, targetID: target, type: EDGE_TYPE] }

    Exchange createProbe(Map doc) { createAs(doc, createShape) }

    Exchange createAs(Map doc, Map<String, Object> variant) {
        String id = doc.id as String
        touched << id
        def ex = http.post(variant.trailingSlash ? CREATE_PATH + '/' : CREATE_PATH, shaped(doc, variant))
        if (ex.success) written << id
        ex
    }

    Exchange putProbe(Map doc) {
        String id = doc.id as String
        touched << id
        def ex = http.put(entityPath(id), shaped(doc, createShape))
        if (ex.success) written << id
        ex
    }

    String defaultArchetype() { opts.archetypes ? opts.archetypes[0] : PROBE_ARCHETYPE }

    /** Applies a create variant, and the adopted relationship form, to a probe write model. */
    Map shaped(Map doc, Map<String, Object> variant) {
        Map d = new LinkedHashMap(doc)
        String id = d.id as String
        if (variant.dropId) d.attributes = (d.attributes as Map).findAll { k, v -> k != 'ID' }
        if (variant.archetype && !d.directArchetypes) d.directArchetypes = [defaultArchetype()]
        if (relationshipForm == 'design') d.relationships = (d.relationships as List).collect { Map r -> designEdge(r, id) }
        if (variant.omitEmpty) d = d.findAll { k, v -> !(v instanceof List && (v as List).isEmpty()) }
        if (variant.legacyKeys) d = d.collectEntries { k, v -> [(LEGACY_KEYS[k as String] ?: k): v] }
        d
    }

    /** A relationship in the design's one-sided form, from the point of view of the entity written. */
    static Map designEdge(Map r, String self) {
        r.sourceID == self ? [TargetID: r.targetID, type: r.type] : [SourceID: r.sourceID, type: r.type]
    }

    static List<Map> relationshipsOf(Map doc) {
        ((doc?.relationships ?: doc?.Relationships ?: []) as List).findAll { it instanceof Map } as List<Map>
    }

    /** Whether a document lists the relationship source → target, in the read shape or the write shape. */
    static boolean hasEdge(Map doc, String source, String target) {
        relationshipsOf(doc).any { r -> (r.source ?: r.sourceID) == source && (r.target ?: r.targetID) == target }
    }

    /** Attribute values from the read model ({@code Attributes: {name: {value}}}) or a search item ({@code attributes}). */
    static Map<String, Object> attributesOf(Map doc) {
        if (doc?.Attributes instanceof Map) return (doc.Attributes as Map).collectEntries { k, v -> [(k): v instanceof Map ? (v as Map).value : v] }
        doc?.attributes instanceof Map ? doc.attributes as Map<String, Object> : [:]
    }

    /** An entity's ID in whichever casing the service uses (ID, id or uid). */
    static String idOf(Map m) { m == null ? null : Scrubber.identity(m) }

    static Object baseTypeOf(Map e) { e.containsKey('BaseType') ? e.BaseType : e.baseType }

    /** A search item's archetypes: by design, search reports directly assigned ones in isArchetypes (C9). */
    static List archetypesOf(Map item) {
        item == null ? null : ((item.directArchetypes ?: item.isArchetypes) ?: []) as List
    }

    /**
     * Adds a finding whose answer depends on reads. If any of those reads failed, the answer would be a guess
     * built on a missing document, so the finding says it cannot tell instead (confidence inconclusive).
     */
    private Finding addFromReads(String task, String key, String question, List<Exchange> reads, Closure<String> answer,
                                 String detail = null, List<Exchange> evidence = []) {
        int bad = reads.findIndexOf { it == null || !it.success }
        if (bad >= 0 || reads.isEmpty()) {
            Exchange failed = bad >= 0 ? reads[bad] : null
            String why = failed == null ? 'no read was possible' : "a read this check depends on returned ${failed.status == -1 ? 'no response' : failed.status}"
            return add(task, key, question, "Cannot tell: ${why}.", 'inconclusive', detail, evidence)
        }
        add(task, key, question, answer(), 'observed', detail, evidence)
    }

    // ------------------------------------------------------------------ helpers: search

    /** Splits a search response into its parts, whatever the envelope looks like. */
    static Map envelope(Object body) {
        def out = [items: null, itemsKey: null, total: null, totalKey: null, cursor: null, cursorKey: null, hasMore: null, topLevelArray: false]
        if (body instanceof List) {
            out.items = body; out.topLevelArray = true
            return out
        }
        if (!(body instanceof Map)) return out
        Map m = body as Map
        String k = ITEM_KEYS.find { m[it] instanceof List }
        if (k) { out.items = m[k]; out.itemsKey = k }
        String tk = TOTAL_KEYS.find { m[it] instanceof Number }
        if (tk) { out.total = m[tk]; out.totalKey = tk }
        String ck = CURSOR_KEYS.find { m.containsKey(it) }
        if (ck) { out.cursorKey = ck; out.cursor = (m[ck] instanceof Map || m[ck] == '') ? null : m[ck] }
        if (m.hasMore instanceof Boolean) out.hasMore = m.hasMore
        out
    }

    static List<Map> items(Exchange ex) { (envelope(ex.body).items ?: []).findAll { it instanceof Map } as List<Map> }

    static List<String> ids(Exchange ex) { items(ex).collect { idOf(it) } }

    static int count(Exchange ex) { (envelope(ex.body).items ?: []).size() }

    static String shape(Exchange ex) {
        if (ex == null) return 'not attempted'
        if (ex.status == -1) return "no response (${ex.error})"
        String ct = ex.contentType ? ex.contentType.split(';')[0] : 'no content type'
        if (ex.body instanceof Map) return "${ct}, JSON object with keys ${(ex.body as Map).keySet().sort()}${allow(ex)}"
        if (ex.body instanceof List) return "${ct}, JSON array"
        if (ex.rawBody) return "${ct}, text body${allow(ex)}"
        "${ct}, empty body${allow(ex)}"
    }

    /** A 405 usually names the methods the path does accept; surface them, as they say what to try instead. */
    private static String allow(Exchange ex) {
        ex.headers['allow'] ? "; Allow: ${ex.headers['allow']}" : ''
    }

    static boolean timestampLike(Object v) {
        (v instanceof String && v ==~ /\d{4}-\d{2}-\d{2}T\d{2}:\d{2}.*/) || (v instanceof Number && v > 946684800000L)
    }

    static String timestampFormat(Object v) {
        if (v instanceof Number) return 'epoch milliseconds (number)'
        String s = v as String
        String frac = (s =~ /T\d{2}:\d{2}:\d{2}(\.\d+)?/) ? ((s =~ /\.(\d+)/) ? "${(s =~ /\.(\d+)/)[0][1].length()}-digit fraction" : 'no fraction') : 'no seconds'
        String zone = s.endsWith('Z') ? 'Z' : ((s =~ /[+-]\d{2}:?\d{2}$/) ? 'numeric offset' : 'no zone')
        "ISO-8601, ${frac}, ${zone}"
    }

    /** One search request: POST to the search path with the fields as a JSON body. */
    private Exchange search(Map<String, ?> body) { http.post(searchPath, body ?: [:]) }

    String getSearchPath() { opts.searchPath ?: SEARCH_PATH }

    Map idFilter(String id, String op = 'EQUALS') { [operation: op, left: contract.idFilterField ?: 'ID', right: id] }

    /**
     * Follows the paging cookie for up to maxPages pages. Returns the items (in memory only).
     * The body may carry objectClass and filter; pageSize and the cookie are added here.
     */
    List<Map> enumerate(Map<String, Object> body = [:], int pageSize = 200, int maxPages = opts.maxPages, Closure afterPage = null) {
        List<Map> all = []
        Map<String, Object> q = new LinkedHashMap<>(body)
        q.pageSize = pageSize
        for (int page = 0; page < maxPages; page++) {
            def ex = search(q)
            if (!ex.success) break
            def env = envelope(ex.body)
            List<Map> got = items(ex)
            all.addAll(got)
            if (afterPage) afterPage(got)
            if (got.empty || env.cursor == null || env.hasMore == false) break
            q.pagedResultsCookie = env.cursor
        }
        all
    }

    /**
     * Finds one entity by search: an EQUALS filter on the ID when the ID filter field is known (P21), or else
     * a scan of at most maxPages pages. Returns [exchange, item, searched]; searched is false when the search
     * failed, or when a scan stopped before the last page without finding the entity.
     */
    Map findInSearch(String id, Integer objectClass = null) {
        Map<String, Object> body = objectClass == null ? [:] : [objectClass: objectClass] as Map<String, Object>
        if (contract.idFilterField) {
            def ex = search(body + [filter: idFilter(id), pageSize: 10])
            return [exchange: ex, item: ex.success ? items(ex).find { idOf(it) == id } : null, searched: ex.success]
        }
        // No ID filter: scan pages. Absence only counts once the scan has reached the last page.
        Map<String, Object> q = new LinkedHashMap<>(body)
        q.pageSize = 200
        Exchange ex = null
        for (int page = 0; page < opts.maxPages; page++) {
            ex = search(q)
            if (!ex.success) return [exchange: ex, item: null, searched: false]
            Map hit = items(ex).find { idOf(it) == id }
            if (hit) return [exchange: ex, item: hit, searched: true]
            def env = envelope(ex.body)
            if (items(ex).empty || env.cursor == null || env.hasMore == false) return [exchange: ex, item: null, searched: true]
            q.pagedResultsCookie = env.cursor
        }
        [exchange: ex, item: null, searched: false]
    }

    /**
     * Reads one entity back: GET (the read model, with relationships), falling back to a search (a search item,
     * with archetypes but no relationships) when GET fails (C1). Returns [via, doc, get, search, found].
     * found is true or false when either read answered, null when neither could.
     */
    Map readBack(String id) {
        def g = http.get(entityPath(id))
        if (g.success && g.body instanceof Map) return [via: 'GET', doc: g.body as Map, get: g, found: true]
        if (g.status == 404) return [via: 'GET', doc: null, get: g, found: false]
        def s = findInSearch(id)
        if (s.searched) return [via: 'search', doc: s.item, get: g, search: s.exchange, found: s.item != null]
        [via: null, doc: null, get: g, search: s.exchange, found: null]
    }

    // ------------------------------------------------------------------ WS0.10 auth

    void checkAuth() {
        // A read of an ID that cannot exist: a valid key should get 404, so 401 means the key itself was refused.
        String path = entityPath(pid('auth-check-does-not-exist'))
        def none = http.get(path, [:], [auth: 'none'])
        def wrong = http.get(path, [:], [auth: 'invalid'])
        def valid = http.get(path, [:])
        String challenge = none.headers['www-authenticate'] ? " with challenge `${none.headers['www-authenticate']}`" : ''
        add('WS0.10', 'auth.status', "How does the service respond to a missing, wrong and valid `${opts.apiKeyHeader}` key?",
            "No key: ${none.status}${challenge}. Wrong key: ${wrong.status}. Valid key: ${valid.status}.",
            'observed', "Checked with a GET of an entity ID that does not exist, so a valid key should get 404. The key is sent raw in the `${opts.apiKeyHeader}` header. The service uses one shared key, so a 403 is not expected.", [none, wrong, valid])
        if (valid.status == -1) {
            aborted = true
            add('WS0.10', 'transport', 'Is the service reachable?', "No: ${valid.error}", 'error', null, [valid])
        } else if (valid.status in [401, 403]) {
            aborted = true
            add('WS0.10', 'auth.valid', 'Is the supplied API key accepted?', "No: ${valid.status}. Remaining checks skipped.", 'error', null, [valid])
        }
    }

    // ------------------------------------------------------------------ seed data (writes)

    List<String> seedIds = []

    /** Three Resources, so paging can be observed on an otherwise empty test instance. */
    void seed() {
        (1..3).each {
            String id = pid("seed-${it}")
            def ex = createProbe(probeDoc(id))
            if (ex.success) seedIds << id
        }
    }

    // ------------------------------------------------------------------ WS0.02 search endpoint and filters

    void checkSearchEndpoint() {
        String path = searchPath
        if (path in CREATE_PATHS) {
            aborted = true
            add('WS0.02', 'search.path', 'Which request serves the entity search?',
                "Refused to search via `POST ${path}`: a POST there creates entities.", 'error')
            return
        }
        def bare = http.post(path, [:])
        boolean ok = bare.success && envelope(bare.body).items != null
        add('WS0.02', 'search.path', 'Which request serves the entity search?',
            ok ? "`POST ${path}` with a JSON body. `{}` returned ${count(bare)} entities." :
                 "`POST ${path}` with `{}` did not return a list of entities: ${bare.status}, ${shape(bare)}.",
            ok ? 'observed' : 'error', null, [bare])
        if (!ok) { aborted = true; return }
        contract.searchPath = path
        // A sample entity for the ID-filter check, preferring one the probe did not create.
        List<Map> found = items(bare)
        searchSampleId = idOf(found.find { !scrubber.isProbeId(idOf(it)) } ?: found.find { idOf(it) != null })
    }

    /** P18, read-only: which base type does each objectClass value select? Answers C6 without creating anything. */
    void checkObjectClassNumbering() {
        List<Exchange> ev = []
        Map<Object, String> selects = [:]
        List<String> lines = OBJECT_CLASS_CANDIDATES.collect { oc ->
            def e = search([objectClass: oc, pageSize: 20]); ev << e
            if (!e.success) return "`${oc instanceof String ? '"' + oc + '"' : oc}` → ${e.status}".toString()
            Map<Object, Integer> names = items(e).countBy { baseTypeOf(it) }
            if (names.size() == 1) selects[oc] = names.keySet().first() as String
            "`${oc instanceof String ? '"' + oc + '"' : oc}` → ${names ? names.collect { k, v -> "${k} ×${v}" }.join(', ') : 'no entities'}".toString()
        }
        contract.objectClassSelects = selects.collectEntries { k, v -> [(k as String): v] }
        String s4 = selects[SUBJECT], s2 = selects[RESOURCE]
        String verdict
        String confidence = 'observed'
        if (s4 == 'Subject' || s2 == 'Resource') {
            verdict = "4 = Subject, 2 = Resource, as P18 assumes${s4 && s2 ? '' : ' (only one of the two had entities to show it)'}."
            contract.baseTypeNumbering = 'P18 (4 = Subject, 2 = Resource)'
        } else if (s4 == 'Resource' || s2 == 'Subject') {
            verdict = '4 = Resource, 2 = Subject: this contradicts P18. Do not create entities through the connector until P18 is revisited (C6).'
            contract.baseTypeNumbering = 'reversed: 4 = Resource, 2 = Subject'
        } else {
            verdict = 'Cannot tell: neither numeric objectClass returned entities of a single base type.'
            confidence = 'inconclusive'
        }
        add('WS0.15', 'baseType.objectClass', 'Which base type does each numeric objectClass select (P18, C6)?', verdict, confidence,
            'Search results name the base type, so a numeric objectClass shows its meaning. ' + lines.join('; '), ev)
    }

    /** P21: which field name filters on the entity ID? Needed for the by-ID search fallback while GET fails (C1). */
    void checkIdFilter() {
        String id = searchSampleId
        if (!id) {
            add('WS0.02', 'search.idFilter', 'Which filter field matches the entity ID (P21)?', 'Cannot tell: no entities visible.', 'inconclusive')
            return
        }
        List<Exchange> ev = []
        List<String> lines = []
        String field = ID_FILTER_FIELDS.find { name ->
            def e = search([filter: [operation: 'EQUALS', left: name, right: id], pageSize: 10]); ev << e
            List<String> got = e.success ? ids(e) : null
            lines << "`${name}` → ${e.success ? "${got.size()} result(s)${got == [id] ? ', exactly the entity' : ''}" : e.status}".toString()
            got == [id]
        }
        contract.idFilterField = field
        add('WS0.02', 'search.idFilter', 'Which filter field matches the entity ID (P21)?',
            field ? "`${field}`: an EQUALS filter on it returns exactly the entity." : 'None of the candidate names returned exactly the entity.',
            field ? 'observed' : 'inconclusive', 'Filtered on the ID of one sampled entity. ' + lines.join('; '), ev)
    }

    // ------------------------------------------------------------------ WS0.03 paging

    void checkPaging() {
        def baseline = search([:])
        def env = envelope(baseline.body)
        int n0 = count(baseline)
        contract.envelope = env.topLevelArray ? 'top-level array' : (env.itemsKey ? "object, items under `${env.itemsKey}`" : 'unknown')
        contract.totalKey = env.totalKey
        contract.cursorKey = env.cursorKey
        add('WS0.03', 'paging.envelope', 'What does a search response look like?',
            "${contract.envelope}; total count: ${env.totalKey ? "`${env.totalKey}`" : 'none'}; next-page cursor: ${env.cursorKey ? "`${env.cursorKey}`" : 'none'}; `hasMore`: ${env.hasMore == null ? 'absent' : 'present'}.",
            'observed', "${n0} items returned with no paging fields${env.total != null ? ", total reported ${env.total}" : ''}.", [baseline])

        if (n0 < 2) {
            add('WS0.03', 'paging.params', 'Do `pageSize` and `pagedResultsCookie` page through the results?', 'Cannot tell: fewer than two entities are visible. Re-run with --allow-writes to seed some.', 'inconclusive', null, [baseline])
            return
        }
        def p1 = search([pageSize: 1])
        def e1 = envelope(p1.body)
        boolean sized = p1.success && count(p1) == 1
        contract.pageSizeParam = sized ? 'pageSize' : null
        def p2 = sized && e1.cursor != null ? search([pageSize: 1, pagedResultsCookie: e1.cursor]) : null
        boolean cursorWorks = p2 != null && p2.success && ids(p2) && ids(p2)[0] != ids(p1)[0]
        contract.cursorParam = cursorWorks ? 'pagedResultsCookie' : null
        String answer = !sized ? "No: `pageSize: 1` returned ${p1.status}, ${count(p1)} items." :
            (e1.cursor == null ? 'Partly: `pageSize` is honoured, but the first page carries no cookie.' :
                (cursorWorks ? "Yes: `pageSize` is honoured, and sending back `${e1.cursorKey}` returns the next page." :
                    "Partly: `pageSize` is honoured, but sending back the cookie did not move to a different entity (${p2?.status})."))
        add('WS0.03', 'paging.params', 'Do `pageSize` and `pagedResultsCookie` page through the results?', answer,
            sized && cursorWorks ? 'observed' : 'inconclusive', "First page `hasMore`: ${e1.hasMore}.", [p1, p2])

        def big = search([pageSize: 10000])
        def thousand = search([pageSize: 1000])
        add('WS0.03', 'paging.maxPageSize', 'What is the largest page the service will return?',
            "Asked for 10000: ${big.status}, ${count(big)} items. Asked for 1000: ${thousand.status}, ${count(thousand)} items.",
            'observed', env.total != null ? "Total reported: ${env.total}. If fewer items came back than both the request and the total, that is the cap." : 'No total is reported, so a cap can only be inferred when the count is a round number below the request.', [big, thousand])

        def a = search([pageSize: 5])
        def b = search([pageSize: 5])
        List<String> ia = ids(a), ib = ids(b)
        String order = ia == ia.toSorted() ? 'sorted by ID' : 'no obvious order'
        add('WS0.03', 'paging.ordering', 'Is the result order stable and what is it ordered by?',
            "Same first page twice: ${ia == ib ? 'identical order' : 'different order'}. Order: ${order}.", 'observed', null, [a, b])
    }

    // ------------------------------------------------------------------ WS0.06 document fields; C1

    void checkDocumentFields() {
        def page = search([pageSize: 20])
        List<Map> found = items(page)
        Map sample = found.find { !scrubber.isProbeId(idOf(it)) } ?: found.find()
        if (!sample) {
            add('WS0.06', 'document.searchFields', 'Which fields does a search item carry?', 'Cannot tell: no entities visible.', 'inconclusive', null, [page])
            return
        }
        Set<String> sk = sample.keySet() as Set<String>
        add('WS0.06', 'document.searchFields', 'Which fields does a search item carry?', fieldComparison(sk, SEARCH_ITEM_KEYS),
            'observed', 'From one search result. Only field names are reported.', [page])
        Object le = sample.lastEvaluated
        contract.lastEvaluatedFormat = timestampLike(le) ? timestampFormat(le) : null
        add('WS0.06', 'document.lastEvaluated', 'In which format is `lastEvaluated` reported?',
            contract.lastEvaluatedFormat ?: "Not a timestamp: ${le == null ? 'absent or null' : le.getClass().simpleName}.",
            contract.lastEvaluatedFormat ? 'observed' : 'inconclusive', null, [page])

        // C1: the first run only ever tried GET on missing and just-created IDs. This tries an existing one.
        String sampleId = idOf(sample)
        def got = sampleId ? http.get(entityPath(sampleId)) : null
        contract.getById = got == null ? null : (got.success ? 'works' : "fails (${got.status})")
        add('WS0.06', 'get.existingEntity', 'Does GET by ID work for an entity that exists (C1)?',
            got == null ? 'Cannot tell: the sampled search result carries no ID.' :
                (got.success ? "Yes: ${got.status}, the read model." : "No: ${got.status}, ${shape(got)}. Reads by ID need `byIdLookup: search` (P21)."),
            got == null ? 'inconclusive' : 'observed', null, [got])
        Map gdoc = got?.success && got.body instanceof Map ? got.body as Map : null
        if (gdoc == null) {
            add('WS0.06', 'document.readModelFields', 'Which fields does the read model (GET) carry?', "Cannot tell: GET ${got == null ? 'was not possible' : "returned ${got.status}"}.", 'inconclusive', null, [got])
            add('WS0.06', 'document.edgeKeys', 'Which keys appear on relationships in the read model?', "Cannot tell: GET ${got == null ? 'was not possible' : "returned ${got.status}"}.", 'inconclusive', null, [got])
            return
        }
        add('WS0.06', 'document.readModelFields', 'Which fields does the read model (GET) carry?', fieldComparison(gdoc.keySet() as Set<String>, READ_MODEL_KEYS), 'observed', null, [got])
        Set<String> edgeKeys = relationshipsOf(gdoc).collectMany { it.keySet() as List } as Set<String>
        add('WS0.06', 'document.edgeKeys', 'Which keys appear on relationships in the read model?',
            edgeKeys ? edgeKeys.sort().join(', ') + (edgeKeys == (['source', 'target', 'type', 'sourceType'] as Set) ? ' (as documented)' : ' (differs from the documented source, target, type, sourceType)') : 'The sampled entity has no relationships.',
            edgeKeys ? 'observed' : 'inconclusive', null, [got])
    }

    /**
     * The structure of a JSON value without its values: field names of objects and list elements, and types.
     * Keys of an object nested two levels down are only counted, because they could be data.
     */
    static String outline(Object v, int depth = 0) {
        if (v instanceof Map) {
            Map m = v as Map
            if (depth >= 2) return "object (${m.size()} fields)"
            return '{' + m.collect { k, x -> "${k}: ${outline(x, depth + 1)}" }.join(', ') + '}'
        }
        if (v instanceof List) {
            List l = v as List
            def first = l.find { it != null }
            return "list of ${l.size()}" + (first == null ? '' : " × ${outline(first, depth)}")
        }
        v == null ? 'null' : (v instanceof Number ? 'number' : (v instanceof Boolean ? 'boolean' : 'string'))
    }

    private static String fieldComparison(Set<String> actual, List<String> documented) {
        def extra = (actual - documented).sort(), missing = (documented - actual).sort()
        String list = actual.toList().sort().join(', ')
        extra || missing ? "${list}. Not documented: ${extra ?: 'none'}; documented but absent: ${missing ?: 'none'}." : "${list} (as documented)."
    }

    // ------------------------------------------------------------------ WS0.08 type discrimination

    void checkTypeDiscrimination() {
        List<Map> found = enumerate([:]).findAll { !scrubber.isProbeId(idOf(it)) }
        if (!found) {
            add('WS0.08', 'types.baseTypes', 'Which base types occur?', 'Cannot tell: no entities other than the probe\'s are visible.', 'inconclusive')
            return
        }
        Closure direct = { Map e -> ((e.directArchetypes ?: []) as List).collect { it as String } }
        Closure policy = { Map e -> ((e.isArchetypes ?: []) as List).collect { it as String } }
        Map<Object, Integer> baseTypes = found.countBy { baseTypeOf(it) }
        Map<String, Set> archetypeBaseTypes = [:].withDefault { [] as Set }
        found.each { e -> (direct(e) + policy(e)).unique().each { a -> archetypeBaseTypes[a] << baseTypeOf(e) } }
        def ambiguous = archetypeBaseTypes.findAll { k, v -> v.size() > 1 }.keySet().sort()
        int none = found.count { !(direct(it) + policy(it)) } as int
        Map<String, Integer> sets = found.countBy { (direct(it) + policy(it)).unique().sort().join('+') ?: '(none)' }
        add('WS0.08', 'types.baseTypes', 'Which base types occur?', baseTypes.sort { a, b -> (a.key as String) <=> (b.key as String) }.collect { k, v -> "${k}: ${v}" }.join(', '),
            'observed', "Sample of ${found.size()} entities over at most ${opts.maxPages} pages.")
        add('WS0.08', 'types.archetypes', 'Do archetypes discriminate types?',
            ambiguous ? "Not on their own: ${ambiguous.join(', ')} appear with more than one base type." : 'In this sample every archetype appears with a single base type.',
            'observed', "Archetypes read from directArchetypes and isArchetypes together; search reports directly assigned ones in isArchetypes (C9). ${none} of ${found.size()} entities have no archetype. ${sets.size()} distinct archetype sets: " + sets.collect { k, v -> "${k} ×${v}" }.join(', '))
        contract.ambiguousArchetypes = ambiguous
    }

    // ------------------------------------------------------------------ P22 manifests

    /** Archetype names per base type from GET /admin/manifests. Only names are recorded; the rest may hold data. */
    void checkManifests() {
        def ex = http.get(MANIFESTS_PATH)
        if (!(ex.success && ex.body instanceof List)) {
            add('WS0.15', 'manifests.archetypes', 'Which archetypes do the manifests define (P22)?',
                ex.status == 404 ? '404: the service has no manifests, so no archetype is defined.' :
                    (ex.success ? "Cannot tell: 200, but not the documented list of manifests. Outline: ${outline(ex.body)}." : "Cannot tell: ${ex.status}, ${shape(ex)}."),
                ex.status == 404 ? 'observed' : 'inconclusive', 'The outline gives field names and types only, never values.', [ex])
            return
        }
        List<Map> ms = (ex.body as List).findAll { it instanceof Map } as List<Map>
        Closure<List<String>> names = { String key -> ms.collectMany { m -> ((m[key] ?: []) as List).findAll { it instanceof Map }.collect { it.name as String } }.findAll().unique().sort() }
        contract.subjectArchetypes = names('subjectArchetypes')
        contract.resourceArchetypes = names('resourceArchetypes')
        add('WS0.15', 'manifests.archetypes', 'Which archetypes do the manifests define (P22)?',
            "${ms.size()} manifest(s). Subject archetypes: ${contract.subjectArchetypes ? (contract.subjectArchetypes as List).join(', ') : 'none'}. Resource archetypes: ${contract.resourceArchetypes ? (contract.resourceArchetypes as List).join(', ') : 'none'}.",
            'observed', 'Every archetype the connector is configured with must be listed here for its base type. Only archetype names are recorded.', [ex])
    }

    // ------------------------------------------------------------------ WS0.05 error bodies

    void checkErrorsReadOnly() {
        def missing = http.get(entityPath(pid('does-not-exist')))
        add('WS0.05', 'errors.getMissing', 'What does GET of a missing entity return?', "${missing.status}: ${shape(missing)}", 'observed', null, [missing])
        def bad = search([filter: [operation: 'NOSUCHOPERATION', left: 'ID', right: pid('x')]])
        add('WS0.05', 'errors.badFilter', 'What does a search with an unknown filter operation return?', "${bad.status}: ${shape(bad)}", 'observed', null, [bad])
        def oc = search([objectClass: 99])
        add('WS0.05', 'errors.badObjectClass', 'What does a search with an unknown objectClass return?', "${oc.status}: ${shape(oc)}", 'observed', null, [oc])
    }

    void checkErrorsWrite() {
        def noId = http.post(CREATE_PATH, [baseType: RESOURCE, attributes: [probe: 'ws0']])
        def junk = http.post(CREATE_PATH, null, [rawBody: '{not json'])
        String ns = pid('non-string')
        def nonString = createProbe(probeDoc(ns) + [attributes: [ID: ns, probe: 'ws0', number: 42]])
        String ghost = pid('never-created')
        def putMissing = putProbe(probeDoc(ghost))
        def delMissing = http.delete(entityPath(pid('never-created-either')))
        add('WS0.05', 'errors.postWithoutId', 'What does a POST without an ID return (C4)?', "${noId.status}: ${shape(noId)}", 'observed', null, [noId])
        add('WS0.05', 'errors.malformedJson', 'What does a malformed JSON body return?', "${junk.status}: ${shape(junk)}", 'observed', null, [junk])
        add('WS0.05', 'errors.nonStringAttribute', 'What does a POST with a non-string attribute value return (P20)?', "${nonString.status}: ${shape(nonString)}", 'observed', null, [nonString])
        Map rb = putMissing.success ? readBack(ghost) : null
        String upsert = rb == null ? '' :
            (rb.found == true ? ". A read-back finds the entity, so PUT creates missing entities (upsert), which the design does not expect." :
             (rb.found == false ? ". A read-back ${rb.via == 'GET' ? '(GET 404)' : 'by search'} does not find it, so the 2xx did not create anything." :
              ". Whether it created the entity cannot be told: GET returned ${rb.get.status} and no search was possible."))
        add('WS0.05', 'errors.putMissing', 'What does a PUT to a missing entity return (C2)?',
            "${putMissing.status}: ${shape(putMissing)}${upsert}", 'observed', null, [putMissing, rb?.get, rb?.search])
        add('WS0.05', 'errors.deleteMissing', 'What does a DELETE of a missing entity return (C3)?', "${delMissing.status}: ${shape(delMissing)}", 'observed', null, [delMissing])
    }

    // ------------------------------------------------------------------ WS0.15 create shape (writes)

    /**
     * Tries each {@link #CREATE_VARIANTS} body once, under its own probe ID, and adopts the first one accepted.
     * Returns false when none is, so the remaining write checks are skipped rather than all failing the same way.
     */
    boolean checkCreateShape() {
        List<Exchange> ev = []
        List<String> lines = []
        Map<String, Object> adopted = null
        CREATE_VARIANTS.eachWithIndex { Map<String, Object> v, int i ->
            def ex = createAs(probeDoc(pid("shape-${i + 1}")), v)
            ev << ex
            lines << "${i + 1}. ${v.name} → ${ex.status}".toString()
            if (ex.success && adopted == null) adopted = v
        }
        contract.createShape = adopted?.name
        if (adopted == null) {
            add('WS0.15', 'create.shape', 'Which create body does the service accept?',
                'None of the variants was accepted, so the other write checks were skipped. The service log for any of these requests should say why.',
                'error', lines.join('; '), ev)
            return false
        }
        createShape = adopted
        String answer = adopted.is(CREATE_VARIANTS[0]) ? 'The EntityDTO with attributes.ID is accepted, as designed (C8).' :
            "The EntityDTO with attributes.ID (C8) is not accepted; the first variant that is: ${adopted.name}. The rest of this run writes that way. This is a change in the service: tell the service team."
        add('WS0.15', 'create.shape', 'Which create body does the service accept?', answer, 'observed',
            "Each variant changes one thing in the designed body. Archetype used: ${defaultArchetype()}. " + lines.join('; '), ev)
        true
    }

    /** Which relationship form does a create accept? Only accepted or refused: the stored result needs GET (C1). */
    void checkRelationshipShape() {
        String T = pid('rel-target'), P = pid('rel-p17'), D = pid('rel-design')
        def t = createProbe(probeDoc(T))
        def p17 = createProbe(probeDoc(P, [edge(P, T)]))
        String saved = relationshipForm
        relationshipForm = 'design'
        def design = createProbe(probeDoc(D, [edge(D, T)]))
        relationshipForm = p17.success || !design.success ? saved : 'design'
        contract.relationshipForm = p17.success ? 'P17 {sourceID, targetID, type}' : (design.success ? 'design {TargetID|SourceID, type}' : null)
        add('WS0.15', 'create.relationshipShape', 'Which relationship form does a create accept (P17)?',
            !t.success ? "Cannot tell: creating the target entity failed (${t.status})." :
                "P17 `{sourceID, targetID, type}` → ${p17.status}; design `{TargetID, type}` → ${design.status}." +
                    (p17.success ? '' : (design.success ? ' Only the design form is accepted: P17 needs revisiting, and the rest of this run writes the design form.' : ' Neither form is accepted.')),
            t.success ? 'observed' : 'inconclusive',
            'Whether the relationship was stored needs GET, which fails (C1); this only shows the body is accepted.', [t, p17, design])
    }

    /**
     * Does search stop returning an entity once it is deleted? The 19 entities the first write run left behind
     * suggest not: either DELETE does nothing, or search keeps deleted entities. Until this answers yes, a search
     * miss cannot prove a deletion (see {@link #cleanup}).
     */
    void checkSearchReflectsDelete() {
        String Z = pid('delete-check')
        def c = createProbe(probeDoc(Z))
        Map before = c.success ? findInSearch(Z) : null
        if (!c.success || !before.searched || !before.item) {
            add('WS0.02', 'search.reflectsDelete', 'Does search stop returning an entity once it is deleted?',
                !c.success ? "Cannot tell: the create failed (${c.status})." : 'Cannot tell: the created entity is not found by search, so its removal cannot be seen there (C5).',
                'inconclusive', null, [c, before?.exchange])
            return
        }
        def d = http.delete(entityPath(Z))
        Map after = findInSearch(Z)
        if (after.item && opts.settleSeconds > 0) {
            sleep(opts.settleSeconds * 1000L)
            after = findInSearch(Z)
        }
        if (!after.searched) {
            add('WS0.02', 'search.reflectsDelete', 'Does search stop returning an entity once it is deleted?', "Cannot tell: the search after DELETE failed (${after.exchange?.status}).", 'inconclusive', null, [c, before.exchange, d, after.exchange])
            return
        }
        contract.searchReflectsDelete = after.item == null
        add('WS0.02', 'search.reflectsDelete', 'Does search stop returning an entity once it is deleted?',
            after.item == null ? "Yes: after DELETE (${d.status}) a search no longer finds it." :
                "No: after DELETE (${d.status}) a search still finds it${opts.settleSeconds > 0 ? " ${opts.settleSeconds}s later" : ''}. Either DELETE does nothing or search keeps deleted entities. GET fails (C1), so the probe cannot tell which; a search miss is not proof of deletion in this run.",
            'observed', null, [c, before.exchange, d, after.exchange])
    }

    /** Read-only: entities left by earlier probe runs (any ID under the probe namespace but not this run's prefix). */
    void checkLeftovers() {
        String ns = ProbeOptions.NAMESPACE
        List<Map> found = contract.idFilterField ?
            enumerate([filter: [operation: 'STARTSWITH', left: contract.idFilterField, right: ns]], 200, opts.maxPages) :
            enumerate([:], 200, opts.maxPages)
        List<String> ids = found.collect { idOf(it) }.findAll { it?.startsWith(ns) && !it.startsWith(opts.prefix) }.unique().sort()
        Map<String, Integer> byRun = ids.countBy { String id -> (id.substring(ns.length()) =~ /^(\d{8}-\d{6}|[^-]+)/).with { it.find() ? it.group(1) : '?' } }
        contract.leftoverProbeEntities = ids.size()
        add('WS0.15', 'probe.leftovers', 'Are entities from earlier probe runs still in the service?',
            ids ? "Yes: ${ids.size()}, from run(s) ${byRun.collect { k, v -> "${k} ×${v}" }.join(', ')}. Remove them by hand; their IDs are in the detail." : 'None found.',
            'observed', ids ? ids.take(60).join(', ') + (ids.size() > 60 ? ", and ${ids.size() - 60} more" : '') : null)
    }

    // ------------------------------------------------------------------ WS0.15 decision checks (writes)

    /** P18 and C5: create a Subject with base type 4 and a Resource with 2; check what search says they are. */
    void checkBaseTypeOnCreate() {
        String S = pid('basetype-4'), R = pid('basetype-2')
        def cs = createProbe(probeDoc(S, [], [:], SUBJECT))
        def cr = createProbe(probeDoc(R, [], [:], RESOURCE))
        if (!cs.success || !cr.success) {
            add('WS0.15', 'baseType.create', 'Is an entity created with base type 4 a Subject (P18)?',
                "The create itself failed: base type 4 → ${cs.status}, base type 2 → ${cr.status}. ${shape(cs.success ? cr : cs)}.", 'error', null, [cs, cr])
            return
        }
        Map fs = findInSearch(S), fr = findInSearch(R)
        Map fsSubject = findInSearch(S, SUBJECT)
        List<Exchange> ev = [cs, cr, fs.exchange, fr.exchange, fsSubject.exchange]
        boolean visible = fs.item != null && fr.item != null
        add('WS0.02', 'search.createdVisible', 'Is a newly created entity found by search (C5)?',
            !fs.searched ? 'Cannot tell: no search was possible.' :
                (visible ? 'Yes: both created entities are found by a search on their ID.' :
                    "No: ${[fs.item ? null : 'the base-type-4 entity', fr.item ? null : 'the base-type-2 entity'].findAll().join(' and ')} not found by a search on the ID. Creates may not be committed, or search may lag (C5)."),
            fs.searched ? 'observed' : 'inconclusive', null, ev)
        if (!fs.item) {
            add('WS0.15', 'baseType.create', 'Is an entity created with base type 4 a Subject (P18)?',
                'Cannot tell: the created entity is not found by search.', 'inconclusive', null, ev)
            return
        }
        Object named4 = baseTypeOf(fs.item), named2 = fr.item ? baseTypeOf(fr.item) : null
        boolean holds = named4 == 'Subject' && (named2 == null || named2 == 'Resource')
        add('WS0.15', 'baseType.create', 'Is an entity created with base type 4 a Subject (P18)?',
            holds ? "Yes: created with 4 it is a `Subject`${named2 ? ', and created with 2 a `Resource`' : ''}; a search with objectClass 4 ${fsSubject.item ? 'finds' : 'does not find'} it." :
                "No: created with 4 it is a `${named4}`${named2 ? ", and created with 2 a `${named2}`" : ''}. P18 is wrong for this service: stop before WS1.02 and before any connector create (C6).",
            'observed', null, ev)
        contract.baseTypeOnCreate = holds ? 'confirmed: 4 = Subject' : "refuted: 4 = ${named4}"
    }

    /** P17, A7, Q07.8 and the relationship endpoints. Relationships are only in the read model, so every answer needs GET. */
    void checkEdgeSemantics() {
        String A = pid('edge-A'), B = pid('edge-B'), C = pid('edge-C')
        Map ab = edge(A, B)

        def postA = createProbe(probeDoc(A, [ab]))
        if (!postA.success) {
            add('WS0.07', 'edges.postToMissing', 'What happens to a relationship to a missing entity on POST (A7)?', "The POST itself failed: ${postA.status}, ${shape(postA)}.", 'error', null, [postA])
            return
        }
        def getA1 = http.get(entityPath(A))
        addFromReads('WS0.07', 'edges.postToMissing', 'What happens to a relationship to a missing entity on POST (A7)?', [getA1], {
            hasEdge(getA1.body as Map, A, B) ? 'Kept: the relationship is stored although the other entity does not exist.' : 'Dropped silently: POST succeeded and the relationship is absent.'
        }, "POST ${postA.status}.", [postA, getA1])

        def dup = createProbe(probeDoc(A, [ab], [probe: 'second']))
        Map rbDup = readBack(A)
        addFromReads('WS0.04', 'create.duplicate', 'What does a duplicate POST return?', [rbDup.found == null ? null : (rbDup.search ?: rbDup.get)], {
            boolean replaced = attributesOf(rbDup.doc).probe == 'second'
            dup.status == 409 ? '409, and the entity is unchanged.' : "${dup.status}${dup.success ? '' : ": ${shape(dup)}"}, and the entity was ${replaced ? 'replaced (upsert)' : 'left unchanged'}."
        }, "Duplicate POST returned ${dup.status}. Read back by ${rbDup.via ?: 'nothing'}.", [dup, rbDup.get, rbDup.search])
        if (dup.success) putProbe(probeDoc(A, [ab]))   // restore the original attributes either way

        def postB = createProbe(probeDoc(B, [ab]))
        def getA2 = http.get(entityPath(A))
        def getB1 = http.get(entityPath(B))
        addFromReads('WS0.07', 'edges.inboundOnCreate', 'Does a relationship in the target\'s POST link it to an existing source?', [getA2], {
            hasEdge(getA2.body as Map, A, B) ? 'Yes: A now shows A → B.' : 'No: A does not show A → B.'
        }, "POST B ${postB.status}, carrying the same relationship as A's.", [postB, getA2])
        addFromReads('WS0.07', 'edges.getShowsInbound', 'Does GET of the target show the relationship, with the other entity as source?', [getB1], {
            hasEdge(getB1.body as Map, A, B) ? 'Yes: B lists A → B.' : "No: B's relationships are ${scrubber.scrub(relationshipsOf(getB1.body as Map))}."
        }, null, [getB1])
        if (getB1.success && relationshipsOf(getB1.body as Map)) {
            Set<String> keys = relationshipsOf(getB1.body as Map).collectMany { it.keySet() as List } as Set<String>
            add('WS0.07', 'edges.readShape', 'Which keys does the read model use for a relationship (P17)?', keys.sort().join(', '), 'observed', null, [getB1])
        }

        def putA = putProbe(probeDoc(A, []))
        def getB2 = http.get(entityPath(B))
        addFromReads('WS0.07', 'edges.sourceOmission', 'Does writing the source without the relationship delete it (P15)?', [getB2], {
            hasEdge(getB2.body as Map, A, B) ? 'No: B still lists A → B.' : 'Yes: B no longer lists A → B.'
        }, "PUT A ${putA.status}.", [putA, getB2])

        def putB = putProbe(probeDoc(B, [ab]))
        def getA3 = http.get(entityPath(A))
        addFromReads('WS0.07', 'edges.inboundOnPut', 'Does a relationship in the target\'s PUT link the entities?', [getA3], {
            hasEdge(getA3.body as Map, A, B) ? 'Yes: A shows A → B again.' : 'No.'
        }, "PUT B ${putB.status}.", [putB, getA3])

        def putB2 = putProbe(probeDoc(B, []))
        def getA4 = http.get(entityPath(A))
        addFromReads('WS0.07', 'edges.targetOmission', 'Does writing the target without the relationship delete it (Q07.8)?', [getA4], {
            hasEdge(getA4.body as Map, A, B) ?
                'No: A still lists A → B. A write only removes relationships where the entity is the source.' :
                'Yes: A no longer lists A → B. Every write must list the relationships where the entity is the target, as design A2 provides.'
        }, "PUT B without the relationship: ${putB2.status}.", [putB2, getA4])

        def putAC = putProbe(probeDoc(A, [ab, edge(A, C)]))
        def getA5 = http.get(entityPath(A))
        if (!putAC.success) {
            add('WS0.07', 'edges.putToMissing', 'What happens to a relationship to a missing entity on PUT?', "Rejected: ${putAC.status}, ${shape(putAC)}.", 'observed', null, [putAC])
        } else {
            addFromReads('WS0.07', 'edges.putToMissing', 'What happens to a relationship to a missing entity on PUT?', [getA5], {
                hasEdge(getA5.body as Map, A, C) ? 'Kept dangling: the relationship to the missing entity is stored.' :
                    (hasEdge(getA5.body as Map, A, B) ? 'Dropped silently: PUT succeeded and only the relationship to the existing entity remains.' : 'Both relationships are absent.')
            }, null, [putAC, getA5])
        }

        String relPath = entityPath(A) + '/relationships'
        def del = http.delete(relPath + '/' + ProbeHttp.encodeSegment(B) + '/' + EDGE_TYPE)
        def getA6 = http.get(entityPath(A))
        def addRel = http.post(relPath, ab)
        def getA7 = http.get(entityPath(A))
        addFromReads('WS0.07', 'edges.relationshipEndpoints', 'Do the relationship endpoints remove and add one relationship?', [getA6, getA7], {
            "DELETE ${del.status}: ${hasEdge(getA6.body as Map, A, B) ? 'A still lists A → B' : 'removed'}. POST ${addRel.status}: ${hasEdge(getA7.body as Map, A, B) ? 'added back' : 'not added'}. The connector does not use them (P15)."
        }, null, [del, getA6, addRel, getA7])

        def delB = http.delete(entityPath(B))
        def getA8 = http.get(entityPath(A))
        def getB3 = http.get(entityPath(B))
        addFromReads('WS0.07', 'edges.deleteCascades', 'Does DELETE remove the relationships touching the entity?', [getA8], {
            hasEdge(getA8.body as Map, A, B) ? 'No: A still lists a relationship to the deleted entity.' : 'Yes: A no longer lists it.'
        }, "DELETE ${delB.status} (${shape(delB)}); GET of the deleted entity then returns ${getB3.status}.", [delB, getA8, getB3])
    }

    /** P20: does an update that omits an attribute remove it? Reads back by GET, or by search while GET fails. */
    void checkAttributeOmission() {
        String X = pid('attrs')
        def c = createProbe(probeDoc(X, [], [keep: 'k1', drop: 'd1']))
        def p = putProbe(probeDoc(X, [], [keep: 'k2']))
        if (!c.success || !p.success) {
            add('WS0.15', 'attributes.omission', 'Does an update that omits an attribute remove it (P20)?', "A write failed: POST ${c.status}, PUT ${p.status}.", 'error', null, [c, p])
            return
        }
        Map rb = readBack(X)
        if (rb.found == false) {
            add('WS0.15', 'attributes.omission', 'Does an update that omits an attribute remove it (P20)?',
                "Cannot tell: the entity is not found after its create and update (by ${rb.via}; C5).", 'inconclusive', null, [c, p, rb.get, rb.search])
            return
        }
        Map attrs = attributesOf(rb.doc)
        addFromReads('WS0.15', 'attributes.omission', 'Does an update that omits an attribute remove it (P20)?', [rb.found ? (rb.search ?: rb.get) : null], {
            String updated = attrs.keep == 'k2' ? '`keep` was updated' : "`keep` reads ${attrs.keep == null ? 'absent' : 'unchanged'}"
            attrs.containsKey('drop') ? "No: the omitted attribute is still there (${updated}). The connector must send empty values explicitly instead of omitting them." :
                "Yes: the omitted attribute is gone, and ${updated}."
        }, "Read back by ${rb.via ?: 'nothing'}.", [c, p, rb.get, rb.search])
    }

    /** P22: does an update replace directArchetypes in full? Archetypes are only in search items. */
    void checkArchetypeReplacement() {
        List<String> pool = (opts.archetypes ?: contract.resourceArchetypes ?: []) as List<String>
        if (pool.size() < 2) {
            add('WS0.15', 'archetypes.replace', 'Does an update replace directArchetypes in full (P22)?',
                'Not tested: it needs two Resource archetypes, from the manifests or --archetypes.', 'not-tested')
            return
        }
        String a1 = pool[0], a2 = pool[1], Y = pid('archetypes')
        def c = createProbe(probeDoc(Y, [], [:], RESOURCE, [a1, a2]))
        Map before = findInSearch(Y)
        def p = putProbe(probeDoc(Y, [], [:], RESOURCE, [a2]))
        Map after = findInSearch(Y)
        List<Exchange> ev = [c, before.exchange, p, after.exchange]
        if (!c.success || !p.success) {
            add('WS0.15', 'archetypes.replace', 'Does an update replace directArchetypes in full (P22)?', "A write failed: POST ${c.status}, PUT ${p.status}. ${shape(c.success ? p : c)}.", 'error', null, ev)
            return
        }
        if (before.searched && after.searched && !(before.item && after.item)) {
            add('WS0.15', 'archetypes.replace', 'Does an update replace directArchetypes in full (P22)?',
                'Cannot tell: the entity is not found by search after its create or update (C5).', 'inconclusive', null, ev)
            return
        }
        List stored = archetypesOf(before.item), now = archetypesOf(after.item)
        addFromReads('WS0.15', 'archetypes.replace', 'Does an update replace directArchetypes in full (P22)?',
            [before.item ? before.exchange : null, after.item ? after.exchange : null], {
            String created = stored == [a1, a2] ? 'Created with both' : "Created with ${stored}"
            now == [a2] ? "Yes: ${created}; after an update listing only `${a2}`, only `${a2}` remains." :
                "No: ${created}; after an update listing only `${a2}`, the entity lists ${now}. The service merges archetypes, so removing one needs the assignment endpoint."
        }, "Archetypes used: ${a1}, ${a2}.", ev)
    }

    // ------------------------------------------------------------------ WS0.09 IDs

    void checkIds() {
        Map<String, String> variants = [
            'space'    : 'id with space',
            'slash'    : 'id/with/slash',
            'question' : 'id?q=1',
            'hash'     : 'id#hash',
            'plus'     : 'id+plus',
            'percent'  : 'id%25',
            'unicode'  : 'idü-ñ-漢',
            'dot'      : 'id.with.dots']
        variants.each { label, suffix -> idRoundTrip(label, pid(suffix)) }
        [64, 255, 256, 1024].each { len ->
            String id = pid('len-'); id = id + 'x' * Math.max(0, len - id.length())
            idRoundTrip("length ${len}", id)
        }
        String mixed = pid('CaseTest')
        def c = createProbe(probeDoc(mixed))
        touched << mixed.toLowerCase()
        Map lower = readBack(mixed.toLowerCase())
        add('WS0.09', 'ids.caseSensitivity', 'Are IDs case-sensitive?',
            !c.success ? "Could not create the test entity: ${c.status}." :
                (lower.found == false ? "Yes: the lower-cased ID is not found (by ${lower.via})." :
                    (lower.found ? "No: the lower-cased ID finds the entity (by ${lower.via})." : "Unclear: GET returned ${lower.get.status} and no search was possible.")),
            c.success ? 'observed' : 'error', null, [c, lower.get, lower.search])
    }

    private void idRoundTrip(String label, String id) {
        def c = createProbe(probeDoc(id))
        if (!c.success) {
            add('WS0.09', "ids.${label}", "Is an ID with ${label} accepted?", "Rejected on create: ${c.status}, ${shape(c)}.", 'observed', null, [c])
            return
        }
        def g = http.get(entityPath(id))
        String back = null
        Map s = null
        if (g.success && g.body instanceof Map) back = idOf(g.body as Map)
        else { s = findInSearch(id); back = idOf(s.item) }
        def d = http.delete(entityPath(id))
        Map gone = readBack(id)
        String via = g.success ? 'read back by GET' : "GET by the encoded ID returned ${g.status}, but a search finds it"
        String answer = back == id ?
            (d.success && gone.found == false ? "Yes: created, ${via} unchanged, and deleted${gone.via == 'search' ? ' (no longer found by search)' : ''}." : "Created and ${via}, but delete returned ${d.status} and removal was ${gone.found == null ? 'not confirmed' : (gone.found ? "not visible: ${gone.via == 'search' ? 'search' : 'GET'} still finds it" : 'confirmed')}.") :
            (back ? "Created, but read back as a different ID (${scrubber.isProbeId(back) ? back : 'other'})." :
                "Created, but neither GET by the encoded ID (${g.status}) nor a search finds it.")
        add('WS0.09', "ids.${label}", "Is an ID with ${label} accepted and addressable when percent-encoded?", answer,
            'observed', "Path used: ${entityPath(id).take(120)}", [c, g, s?.exchange, d, gone.get, gone.search])
    }

    // ------------------------------------------------------------------ WS0.03 stability under change (writes)

    void checkPagingStability() {
        if (!(contract.pageSizeParam && contract.cursorParam && contract.idFilterField)) {
            add('WS0.03', 'paging.updateDuringEnumeration', 'Can an entity updated mid-enumeration be skipped or returned twice?',
                'Not tested: paging by cookie and the ID filter field must both be known first.', 'not-tested')
            return
        }
        List<String> stab = (1..4).collect { pid("stab-${it}") }
        int created = stab.count { createProbe(probeDoc(it)).success } as int
        if (created < 4) {
            add('WS0.03', 'paging.updateDuringEnumeration', 'Can an entity updated mid-enumeration be skipped or returned twice?', 'Could not create the four test entities.', 'error')
            return
        }
        Map<String, Integer> seen = stab.collectEntries { [(it): 0] }
        boolean touchedOne = false
        enumerate([filter: idFilter(pid('stab-'), 'STARTSWITH')], 1, 50) { List<Map> page ->
            page.each { if (seen.containsKey(idOf(it))) seen[idOf(it)]++ }
            def hit = page.find { seen.containsKey(idOf(it)) }
            if (hit && !touchedOne) {
                touchedOne = true
                putProbe(probeDoc(idOf(hit), [], [probe: 'touched']))
            }
        }
        def dups = seen.findAll { k, v -> v > 1 }.keySet()
        def skipped = seen.findAll { k, v -> v == 0 }.keySet()
        add('WS0.03', 'paging.updateDuringEnumeration', 'Can an entity updated mid-enumeration be skipped or returned twice?',
            skipped ? "Skipped: ${skipped.size()} of 4 entities never appeared. Recon could miss entities; enumeration needs a stable order." :
                (dups ? 'Returned twice: the updated entity reappeared later. Harmless for recon.' : 'Neither: each entity appeared exactly once.'),
            'observed', 'Page size 1, a STARTSWITH filter on the probe IDs, one entity updated after the first page that contained a test entity.')
    }

    // ------------------------------------------------------------------ WS0.10 limits

    void checkLimitHeaders() {
        Set<String> names = http.exchanges.collectMany { it.headers.keySet() as List }.findAll { it ==~ /(?i).*(rate|retry|throttl|quota).*/ } as Set<String>
        List<Exchange> carrying = http.exchanges.findAll { ex -> ex.headers.keySet().any { it in names } }
        add('WS0.10', 'limits.headers', 'Does the service advertise rate limits?',
            names ? "Headers seen: ${names.sort().join(', ')}." : "No rate-limit or Retry-After headers were seen in ${http.exchanges.size()} responses.",
            names ? 'observed' : 'inconclusive', 'The probe does not try to trigger throttling. A 429 policy must come from the service team.', carrying.take(20))
    }

    // ------------------------------------------------------------------ cleanup

    /**
     * Deletes every probe ID, then checks each is gone. A GET 404 proves it. While GET fails (C1), a search that
     * finds nothing proves it only once {@code search.reflectsDelete} has shown that search drops deleted entities;
     * otherwise the ID is listed as unconfirmed. An ID whose writes were all refused needs no proof, but if a search
     * finds it anyway, that is reported too.
     */
    void cleanup() {
        http.currentCheck = 'cleanup'
        touched.toList().reverse().each { id ->
            def d = http.delete(entityPath(id))
            if (!(d.success || d.status == 404)) cleanupProblems << "DELETE ${id} returned ${d.status}".toString()
        }
        boolean searchProves = contract.searchReflectsDelete == true
        touched.each { id ->
            Map rb = readBack(id)
            boolean wasWritten = id in written
            if (rb.found == true) cleanupProblems << "${id} still ${rb.via == 'search' ? 'found by search' : 'exists'} after cleanup".toString()
            else if (rb.found == false && rb.via == 'GET') cleanupConfirmedBy[id] = 'GET 404'
            else if (!wasWritten) return
            else if (rb.found == false && searchProves) cleanupConfirmedBy[id] = 'search'
            else if (rb.found == false) cleanupUnconfirmed << id
            else cleanupProblems << "could not confirm ${id} is gone: GET returned ${rb.get.status == -1 ? 'no response' : rb.get.status} and no search was possible".toString()
        }
    }
}
