package au.com.azwork.idm.graphentity.probe

import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * WS0 contract probe. Observes the graph entity service and answers as many of the delivery plan's WS0
 * questions (WS0.02–WS0.10) as can be answered by observation, recording every exchange as evidence.
 *
 * <p>Read-only by default: only GET requests are issued. With {@code allowWrites}, it also creates,
 * replaces and deletes entities whose ID starts with a per-run prefix ({@code ws0-probe-<runId>-}), and
 * removes them all at the end, including after a failure. It never touches any other entity.
 *
 * <p>Results are left in {@link #findings} and {@link #contract}; {@link ProbeReport} writes them out.
 */
class ContractProbe {

    static final String EPOCH = '1970-01-01T00:00:00Z'
    static final String EDGE_TYPE = 'Ws0ProbeEdge'
    static final List<String> STANDARD_KEYS = ['ID', 'BaseType', 'attributes', 'Relationships', 'directArchetypes']

    static final List<String> SEARCH_PATHS = ['/admin/entities', '/admin/entities/']
    static final List<String> TIMESTAMP_PARAMS = ['updatedAfter', 'modifiedSince', 'updatedSince', 'since', 'lastUpdatedAfter', 'changedSince', 'from']
    static final List<String> PAGE_SIZE_PARAMS = ['pageSize', 'limit', 'size', 'page_size', 'per_page', '_pageSize', 'count', 'top', 'max']
    static final List<String> OFFSET_PARAMS = ['offset', 'start', 'skip', 'from', 'startIndex', '_pagedResultsOffset']
    static final List<String> PAGE_NUMBER_PARAMS = ['page', 'pageNumber', 'pageIndex']
    static final List<String> CURSOR_PARAMS = ['cursor', 'nextCursor', 'continuationToken', 'pageToken', 'after', '_pagedResultsCookie']
    static final List<String> ITEM_KEYS = ['items', 'results', 'data', 'entities', 'resources', 'content', 'value', 'result']
    static final List<String> TOTAL_KEYS = ['totalCount', 'total', 'count', 'totalResults', 'total_count', 'resultCount']
    static final List<String> CURSOR_KEYS = ['nextCursor', 'next', 'cursor', 'continuationToken', 'nextPageToken', 'pageToken', 'pagedResultsCookie', '_pagedResultsCookie']

    final ProbeOptions opts
    final ProbeHttp http
    final Scrubber scrubber
    final List<Finding> findings = []
    /** Every probe ID a write was attempted for, so cleanup can remove it whatever the outcome. */
    final Set<String> touched = new LinkedHashSet<>()
    final List<String> cleanupProblems = []
    /** What was discovered about the API, for the "suggested values" part of the report. */
    final Map<String, Object> contract = [:]

    private boolean aborted = false

    ContractProbe(ProbeOptions opts, ProbeHttp http = null) {
        this.opts = opts
        this.http = http ?: new ProbeHttp(opts.baseUrl, opts.username, opts.password, Duration.ofSeconds(opts.timeoutSeconds))
        this.scrubber = new Scrubber(opts.prefix)
    }

    // ------------------------------------------------------------------ orchestration

    ContractProbe run() {
        try {
            step('WS0.10') { checkAuth() }
            if (opts.allowWrites) step('WS0.07') { seed() }
            step('WS0.02') { checkSearchEndpoint() }
            step('WS0.03') { checkPaging() }
            step('WS0.06') { checkDocumentFields() }
            step('WS0.08') { checkTypeDiscrimination() }
            step('WS0.05') { checkErrorsReadOnly() }
            if (opts.allowWrites) {
                step('WS0.07') { checkEdgeSemantics() }
                step('WS0.05') { checkErrorsWrite() }
                step('WS0.09') { checkIds() }
                step('WS0.03') { checkPagingStability() }
            } else {
                notTested('WS0.04', 'create.duplicate', 'What does a duplicate POST return?')
                notTested('WS0.07', 'edges', 'Edge semantics on POST, PUT, GET and DELETE')
                notTested('WS0.09', 'ids', 'ID characters, length and case sensitivity')
                notTested('WS0.02', 'search.boundary', 'Is the timestamp filter strictly-after or inclusive?')
                notTested('WS0.03', 'paging.updateDuringEnumeration', 'Can an entity updated mid-enumeration be skipped or returned twice?')
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

    private void notTested(String task, String key, String question) {
        add(task, key, question, 'Not tested: needs --allow-writes against a test instance.', 'not-tested')
    }

    // ------------------------------------------------------------------ helpers

    String entityPath(String id) { '/admin/entities/' + ProbeHttp.encodeSegment(id) }

    String pid(String suffix) { opts.prefix + suffix }

    Map probeDoc(String id, List<Map> rels = [], Map extra = [:]) {
        [ID: id, BaseType: 2, attributes: [ID: id, probe: 'ws0'] + extra, Relationships: rels, directArchetypes: ['Ws0Probe']]
    }

    Exchange createProbe(Map doc) {
        touched << (doc.ID as String)
        http.post('/admin/entities/', doc)
    }

    Exchange putProbe(Map doc) {
        touched << (doc.ID as String)
        http.put(entityPath(doc.ID as String), doc)
    }

    Map getDoc(String id) {
        def ex = http.get(entityPath(id))
        ex.success && ex.body instanceof Map ? (ex.body as Map) : null
    }

    static boolean hasEdge(Map doc, String field, String otherId) {
        (doc?.Relationships ?: []).any { it instanceof Map && it[field] == otherId }
    }

    /** Splits a search response into its parts, whatever the envelope looks like. */
    static Map envelope(Object body) {
        def out = [items: null, itemsKey: null, total: null, totalKey: null, cursor: null, cursorKey: null, topLevelArray: false]
        if (body instanceof List) {
            out.items = body; out.topLevelArray = true
            return out
        }
        if (!(body instanceof Map)) return out
        Map m = body as Map
        String k = ITEM_KEYS.find { m[it] instanceof List } ?:
            m.find { kk, v -> v instanceof List && (v as List).every { it instanceof Map && it.containsKey('ID') } }?.key
        if (k) { out.items = m[k]; out.itemsKey = k }
        String tk = TOTAL_KEYS.find { m[it] instanceof Number }
        if (tk) { out.total = m[tk]; out.totalKey = tk }
        String ck = CURSOR_KEYS.find { m[it] != null && !(m[it] instanceof Map) && m[it] != '' }
        if (ck) { out.cursor = m[ck]; out.cursorKey = ck }
        out
    }

    static List<String> ids(Exchange ex) {
        (envelope(ex.body).items ?: []).findAll { it instanceof Map }.collect { it.ID as String }
    }

    static int count(Exchange ex) { (envelope(ex.body).items ?: []).size() }

    static String shape(Exchange ex) {
        if (ex.status == -1) return "no response (${ex.error})"
        String ct = ex.contentType ? ex.contentType.split(';')[0] : 'no content type'
        if (ex.body instanceof Map) return "${ct}, JSON object with keys ${(ex.body as Map).keySet().sort()}"
        if (ex.body instanceof List) return "${ct}, JSON array"
        if (ex.rawBody) return "${ct}, text body"
        "${ct}, empty body"
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

    private Map<String, String> baseQuery(String since = EPOCH) {
        contract.timestampParam ? [(contract.timestampParam as String): since] : [:]
    }

    private Exchange search(Map<String, ?> q) { http.get(contract.searchPath as String, q) }

    /** Follows the discovered paging protocol for up to maxPages pages. Returns the items (in memory only). */
    List<Map> enumerate(String since = EPOCH, int pageSize = 200, int maxPages = opts.maxPages, Closure afterPage = null) {
        List<Map> all = []
        Map<String, Object> q = baseQuery(since)
        if (contract.pageSizeParam) q[contract.pageSizeParam as String] = pageSize as String
        int offset = 0
        for (int page = 0; page < maxPages; page++) {
            def ex = search(q)
            if (!ex.success) break
            def env = envelope(ex.body)
            List<Map> items = (env.items ?: []).findAll { it instanceof Map } as List<Map>
            all.addAll(items)
            if (afterPage) afterPage(items)
            if (items.empty) break
            if (contract.cursorParam && env.cursor != null) {
                q[contract.cursorParam as String] = env.cursor as String
            } else if (contract.offsetParam && contract.pageSizeParam) {
                offset += items.size()
                if (env.total instanceof Number && offset >= (env.total as Number).intValue()) break
                q[contract.offsetParam as String] = offset as String
            } else {
                break
            }
        }
        all
    }

    // ------------------------------------------------------------------ WS0.10 auth

    void checkAuth() {
        String path = opts.searchPath ?: SEARCH_PATHS[0]
        def none = http.get(path, [:], [auth: 'none'])
        def wrong = http.get(path, [:], [auth: 'invalid'])
        def valid = http.get(path, [:])
        String challenge = none.headers['www-authenticate'] ? " with challenge `${none.headers['www-authenticate']}`" : ' with no challenge header'
        add('WS0.10', 'auth.status', 'How does the service respond to missing, wrong and valid Basic credentials?',
            "No credentials: ${none.status}${challenge}. Wrong credentials: ${wrong.status}. Valid credentials: ${valid.status}.",
            'observed', '403 (authenticated but not permitted) cannot be provoked without a second, under-privileged account.', [none, wrong, valid])
        if (valid.status == -1) {
            aborted = true
            add('WS0.10', 'transport', 'Is the service reachable?', "No: ${valid.error}", 'error', null, [valid])
        } else if (valid.status in [401, 403]) {
            aborted = true
            add('WS0.10', 'auth.valid', 'Are the supplied credentials accepted?', "No: ${valid.status}. Remaining checks skipped.", 'error', null, [valid])
        }
    }

    // ------------------------------------------------------------------ seed data (writes)

    List<String> seedIds = []

    void seed() {
        (1..3).each {
            String id = pid("seed-${it}")
            def ex = createProbe(probeDoc(id))
            if (ex.success) seedIds << id
        }
    }

    // ------------------------------------------------------------------ WS0.02 search endpoint

    void checkSearchEndpoint() {
        List<String> paths = opts.searchPath ? [opts.searchPath] : SEARCH_PATHS
        List<Exchange> tried = paths.collect { http.get(it) }
        def chosen = tried.find { it.success && envelope(it.body).items != null }
        add('WS0.02', 'search.path', 'Which path serves the entity search?',
            chosen ? "`${chosen.path}`" : 'Neither candidate returned a list of entities.',
            chosen ? 'observed' : 'error',
            tried.collect { "GET ${it.path} → ${it.status}, ${shape(it)}" }.join('; '), tried)
        if (!chosen) { aborted = true; return }
        contract.searchPath = chosen.path

        String future = DateTimeFormatter.ISO_INSTANT.format(Instant.now().plus(3650, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS))
        List<String> lines = []
        List<Exchange> ev = []
        String honoured = null
        boolean anyData = false
        for (name in TIMESTAMP_PARAMS) {
            def e1 = http.get(contract.searchPath as String, [(name): EPOCH])
            def e2 = http.get(contract.searchPath as String, [(name): future])
            ev << e1 << e2
            int c1 = count(e1), c2 = count(e2)
            anyData = anyData || c1 > 0
            String verdict = !e1.success ? "rejected (${e1.status})" : (c1 > 0 && e2.success && c2 < c1 ? 'honoured' : (c1 == 0 ? 'no data to tell' : 'ignored'))
            lines << "`${name}`: epoch → ${e1.status}/${c1} items, far future → ${e2.status}/${c2} items: ${verdict}"
            if (verdict == 'honoured' && !honoured) honoured = name
        }
        contract.timestampParam = honoured
        add('WS0.02', 'search.timestampParam', 'What is the timestamp search parameter called?',
            honoured ? "`${honoured}`" : (anyData ? 'None of the candidate names was honoured.' : 'Cannot tell: the search returned no entities.'),
            honoured ? 'observed' : 'inconclusive',
            "A parameter is honoured when a far-future value returns fewer entities than the epoch. " + lines.join('; '), ev)

        if (!honoured) return
        Map<String, String> formats = [
            'ISO-8601, seconds, Z'       : '1970-01-01T00:00:00Z',
            'ISO-8601, milliseconds, Z'  : '1970-01-01T00:00:00.000Z',
            'ISO-8601, +00:00 offset'    : '1970-01-01T00:00:00+00:00',
            'epoch milliseconds'         : '0',
            'date only'                  : '1970-01-01']
        int reference = count(ev[TIMESTAMP_PARAMS.indexOf(honoured) * 2])
        List<String> accepted = []
        List<Exchange> fev = []
        formats.each { label, value ->
            def e = http.get(contract.searchPath as String, [(honoured): value])
            fev << e
            if (e.success && count(e) == reference) accepted << label
        }
        contract.acceptedDatetimeFormats = accepted
        add('WS0.02', 'search.datetimeFormats', "Which datetime formats does `${honoured}` accept?",
            accepted ? accepted.join('; ') : 'None of the tried formats behaved like the ISO epoch value.',
            'observed', formats.collect { l, v -> "${l} (`${v}`) → ${fev[formats.keySet().toList().indexOf(l)].status}" }.join('; '), fev)
    }

    // ------------------------------------------------------------------ WS0.03 paging

    void checkPaging() {
        Map<String, String> base = baseQuery()
        def baseline = search(base)
        def env = envelope(baseline.body)
        int n0 = count(baseline)
        contract.envelope = env.topLevelArray ? 'top-level array' : (env.itemsKey ? "object, items under `${env.itemsKey}`" : 'unknown')
        contract.totalKey = env.totalKey
        contract.cursorKey = env.cursorKey
        add('WS0.03', 'paging.envelope', 'What does a search response look like?',
            "${contract.envelope}; total count: ${env.totalKey ? "`${env.totalKey}`" : 'none'}; next-page cursor: ${env.cursorKey ? "`${env.cursorKey}`" : 'none'}; Link header: ${baseline.headers['link'] ? 'present' : 'none'}.",
            'observed', "${n0} items returned with no paging parameters${env.total != null ? ", total reported ${env.total}" : ''}.", [baseline])

        if (n0 < 2) {
            add('WS0.03', 'paging.params', 'Which parameters control paging?', 'Cannot tell: fewer than two entities are visible. Re-run with --allow-writes to seed some.', 'inconclusive', null, [baseline])
            return
        }

        List<Exchange> ev = []
        String sizeParam = PAGE_SIZE_PARAMS.find { name ->
            def e = search(base + [(name): '1']); ev << e
            e.success && count(e) == 1
        }
        contract.pageSizeParam = sizeParam
        add('WS0.03', 'paging.pageSizeParam', 'Which parameter sets the page size?', sizeParam ? "`${sizeParam}`" : 'None of the candidates.',
            sizeParam ? 'observed' : 'inconclusive', "Tried: ${PAGE_SIZE_PARAMS.join(', ')}.", ev)
        if (!sizeParam) return

        def p1 = search(base + [(sizeParam): '1'])
        String first = ids(p1) ? ids(p1)[0] : null
        ev = [p1]
        String offsetParam = OFFSET_PARAMS.find { name ->
            def e = search(base + [(sizeParam): '1', (name): '1']); ev << e
            e.success && ids(e) && ids(e)[0] != first
        }
        String pageNumberParam = PAGE_NUMBER_PARAMS.find { name ->
            def a = search(base + [(sizeParam): '1', (name): '1']); def b = search(base + [(sizeParam): '1', (name): '2']); ev << a << b
            a.success && b.success && ids(a) && ids(b) && ids(a)[0] != ids(b)[0]
        }
        def p1env = envelope(p1.body)
        String cursorParam = null
        if (p1env.cursor != null) {
            cursorParam = CURSOR_PARAMS.find { name ->
                def e = search(base + [(sizeParam): '1', (name): p1env.cursor as String]); ev << e
                e.success && ids(e) && ids(e)[0] != first
            }
        }
        contract.offsetParam = offsetParam
        contract.pageNumberParam = pageNumberParam
        contract.cursorParam = cursorParam
        add('WS0.03', 'paging.params', 'Is paging offset-based, page-number based or cursor-based, and with which parameter?',
            [offsetParam ? "offset via `${offsetParam}`" : null, pageNumberParam ? "page number via `${pageNumberParam}`" : null,
             cursorParam ? "cursor: response `${p1env.cursorKey}` sent back as `${cursorParam}`" : (p1env.cursor != null ? "response carries `${p1env.cursorKey}` but no candidate parameter accepted it" : null)]
                .findAll().join('; ') ?: 'No offset, page-number or cursor parameter changed the results.',
            (offsetParam || pageNumberParam || cursorParam) ? 'observed' : 'inconclusive', null, ev)

        def big = search(base + [(sizeParam): '10000'])
        def thousand = search(base + [(sizeParam): '1000'])
        add('WS0.03', 'paging.maxPageSize', 'What is the largest page the service will return?',
            "Asked for 10000: ${big.status}, ${count(big)} items. Asked for 1000: ${thousand.status}, ${count(thousand)} items.",
            'observed', env.total != null ? "Total reported: ${env.total}. If fewer items came back than both the request and the total, that is the cap." : 'No total is reported, so a cap can only be inferred when the count is a round number below the request.', [big, thousand])

        def a = search(base + [(sizeParam): '5'])
        def b = search(base + [(sizeParam): '5'])
        List<String> ia = ids(a), ib = ids(b)
        String tsField = (envelope(a.body).items ?: []).findAll { it instanceof Map }.collectMany { (it as Map).findAll { k, v -> !(k in STANDARD_KEYS) && timestampLike(v) }.keySet() as List }.find()
        def tsValues = tsField ? (envelope(a.body).items as List<Map>).collect { it[tsField] as String } : []
        String order = ia == ia.toSorted() ? 'sorted by ID' : (tsField && tsValues == tsValues.toSorted() ? "sorted by `${tsField}` ascending" : 'no obvious order')
        add('WS0.03', 'paging.ordering', 'Is the result order stable and what is it ordered by?',
            "Same first page twice: ${ia == ib ? 'identical order' : 'different order'}. Order: ${order}.", 'observed', null, [a, b])
    }

    // ------------------------------------------------------------------ WS0.06 document fields

    void checkDocumentFields() {
        def page = search(baseQuery() + (contract.pageSizeParam ? [(contract.pageSizeParam as String): '20'] : [:]))
        List<Map> items = (envelope(page.body).items ?: []).findAll { it instanceof Map } as List<Map>
        Map sample = items.find { !scrubber.isProbeId(it.ID) } ?: items.find()
        if (!sample) {
            add('WS0.06', 'document.fields', 'Which fields does a document carry beyond §2.1?', 'Cannot tell: no entities visible.', 'inconclusive', null, [page])
            return
        }
        def extra = sample.findAll { k, v -> !(k in STANDARD_KEYS) }
        String fieldList = extra ? extra.collect { k, v -> "`${k}` (${timestampLike(v) ? timestampFormat(v) : v?.getClass()?.simpleName ?: 'null'})" }.join(', ') : 'none'
        String lu = extra.keySet().find { it ==~ /(?i).*(updated|modified|changed|timestamp).*/ && timestampLike(extra[it]) } ?:
            extra.keySet().find { timestampLike(extra[it]) }
        contract.lastUpdatedField = lu
        contract.lastUpdatedFormat = lu ? timestampFormat(sample[lu]) : null
        def got = http.get(entityPath(sample.ID as String))
        Map gdoc = got.body instanceof Map ? got.body as Map : [:]
        Set<String> sk = sample.keySet() as Set<String>, gk = gdoc.keySet() as Set<String>
        add('WS0.06', 'document.fields', 'Which fields does a document carry beyond §2.1?', fieldList, 'observed',
            'From one search result. Only field names and value formats are reported.', [page])
        add('WS0.06', 'document.lastUpdatedField', 'Which field carries the last-updated timestamp, in which format?',
            lu ? "`${lu}`, ${contract.lastUpdatedFormat}" : 'No timestamp-like field found.', lu ? 'inferred' : 'inconclusive',
            'Inferred from the field name and value format.', [page])
        add('WS0.06', 'document.searchVsGet', 'Do search results and GET return the same document shape?',
            got.success ? (sk == gk ? 'Yes, the same top-level fields.' : "No. Only in search: ${(sk - gk).sort()}. Only in GET: ${(gk - sk).sort()}.") : "GET of the sampled entity returned ${got.status}.",
            got.success ? 'observed' : 'inconclusive', null, [page, got])
        Set<String> edgeKeys = ([sample, gdoc].collectMany { (it?.Relationships ?: []) as List }.findAll { it instanceof Map }.collectMany { (it as Map).keySet() as List }) as Set<String>
        add('WS0.06', 'document.edgeKeys', 'Which keys appear on relationship entries?',
            edgeKeys ? edgeKeys.sort().join(', ') + (edgeKeys - ['TargetID', 'SourceID', 'type'] ? ' (more than §2.2 documents)' : '') : 'The sampled entity has no relationships.',
            edgeKeys ? 'observed' : 'inconclusive', null, [page, got])
    }

    // ------------------------------------------------------------------ WS0.08 type discrimination

    void checkTypeDiscrimination() {
        List<Map> items = enumerate().findAll { !scrubber.isProbeId(it.ID) }
        if (!items) {
            add('WS0.08', 'types.baseTypes', 'Which BaseType values occur, and do archetypes discriminate types?', 'Cannot tell: no entities other than the probe\'s are visible.', 'inconclusive')
            return
        }
        Map<Object, Integer> baseTypes = items.countBy { it.BaseType }
        Map<String, Set> archetypeBaseTypes = [:].withDefault { [] as Set }
        items.each { e -> (e.directArchetypes ?: []).each { a -> archetypeBaseTypes[a as String] << e.BaseType } }
        def ambiguous = archetypeBaseTypes.findAll { k, v -> v.size() > 1 }.keySet().sort()
        int none = items.count { !(it.directArchetypes) } as int
        Map<String, Integer> sets = items.countBy { ((it.directArchetypes ?: []) as List).collect { it as String }.sort().join('+') ?: '(none)' }
        add('WS0.08', 'types.baseTypes', 'Which BaseType values occur?', baseTypes.sort { a, b -> (a.key as String) <=> (b.key as String) }.collect { k, v -> "${k}: ${v}" }.join(', '),
            'observed', "Sample of ${items.size()} entities over at most ${opts.maxPages} pages.")
        add('WS0.08', 'types.archetypes', 'Do archetypes discriminate types?',
            ambiguous ? "Not on their own: ${ambiguous.join(', ')} appear with more than one BaseType." : 'In this sample every archetype appears with a single BaseType.',
            'observed', "${sets.size()} distinct archetype sets; ${none} entities have no archetypes. Sets: " + sets.collect { k, v -> "${k} ×${v}" }.join(', '))
        contract.ambiguousArchetypes = ambiguous
    }

    // ------------------------------------------------------------------ WS0.05 error bodies

    void checkErrorsReadOnly() {
        def missing = http.get(entityPath(pid('does-not-exist')))
        add('WS0.05', 'errors.getMissing', 'What does GET of a missing entity return?', "${missing.status}: ${shape(missing)}", 'observed', null, [missing])
        if (contract.timestampParam) {
            def bad = search([(contract.timestampParam as String): 'not-a-date'])
            add('WS0.05', 'errors.badTimestamp', 'What does a malformed timestamp return?', "${bad.status}: ${shape(bad)}", 'observed', null, [bad])
        }
    }

    void checkErrorsWrite() {
        def noId = http.post('/admin/entities/', [BaseType: 2, attributes: [probe: 'ws0']])
        def junk = http.post('/admin/entities/', null, [rawBody: '{not json'])
        String ghost = pid('never-created')
        def putMissing = putProbe(probeDoc(ghost))
        def delMissing = http.delete(entityPath(pid('never-created-either')))
        add('WS0.05', 'errors.postWithoutId', 'What does a POST without an ID return?', "${noId.status}: ${shape(noId)}", 'observed', null, [noId])
        add('WS0.05', 'errors.malformedJson', 'What does a malformed JSON body return?', "${junk.status}: ${shape(junk)}", 'observed', null, [junk])
        add('WS0.05', 'errors.putMissing', 'What does a PUT to a missing entity return?',
            "${putMissing.status}: ${shape(putMissing)}" + (putMissing.success ? '. The PUT created the entity (upsert), which the design does not expect.' : ''),
            'observed', null, [putMissing])
        add('WS0.05', 'errors.deleteMissing', 'What does a DELETE of a missing entity return?', "${delMissing.status}: ${shape(delMissing)}", 'observed', null, [delMissing])
    }

    // ------------------------------------------------------------------ WS0.07 / WS0.04 / WS0.06 edges and writes

    void checkEdgeSemantics() {
        String A = pid('edge-A'), B = pid('edge-B'), C = pid('edge-C')
        Map toB = [TargetID: B, type: EDGE_TYPE]

        def postA = createProbe(probeDoc(A, [toB]))
        if (!postA.success) {
            add('WS0.07', 'edges.postToMissing', 'What happens to an edge to a missing entity on POST?', "The POST itself failed: ${postA.status}, ${shape(postA)}.", 'error', null, [postA])
            return
        }
        def getA1 = http.get(entityPath(A))
        boolean kept = hasEdge(getA1.body as Map, 'TargetID', B)
        add('WS0.07', 'edges.postToMissing', 'What happens to an edge to a missing entity on POST (A7)?',
            kept ? 'Kept: the edge is stored although the other entity does not exist.' : 'Dropped silently: POST succeeded and the edge is absent.',
            'observed', "POST ${postA.status}.", [postA, getA1])

        def dup = createProbe(probeDoc(A, [toB], [probe: 'second']))
        def getA2 = http.get(entityPath(A))
        boolean replaced = (getA2.body as Map)?.attributes?.probe == 'second'
        add('WS0.04', 'create.duplicate', 'What does a duplicate POST return?',
            dup.status == 409 ? '409, and the entity is unchanged.' : (dup.success ? "${dup.status}, and the entity was ${replaced ? 'replaced (upsert)' : 'left unchanged'}." : "${dup.status}: ${shape(dup)}, entity ${replaced ? 'replaced' : 'unchanged'}."),
            'observed', null, [dup, getA2])

        def postB = createProbe(probeDoc(B, [[SourceID: A, type: EDGE_TYPE]]))
        def getA3 = http.get(entityPath(A))
        def getB1 = http.get(entityPath(B))
        add('WS0.07', 'edges.inboundOnCreate', 'Does an inbound edge asserted in a POST link to an existing entity?',
            hasEdge(getA3.body as Map, 'TargetID', B) ? 'Yes: A now shows the outbound edge to B.' : 'No: A does not show an edge to B.',
            'observed', "POST B ${postB.status}.", [postB, getA3])
        add('WS0.07', 'edges.getShowsInbound', 'Does GET show inbound edges as SourceID?',
            hasEdge(getB1.body as Map, 'SourceID', A) ? 'Yes: B shows SourceID A.' : "No: B's relationships are ${scrubber.scrub((getB1.body as Map)?.Relationships)}.",
            'observed', null, [getB1])

        def putA = putProbe(probeDoc(A, []))
        def getB2 = http.get(entityPath(B))
        add('WS0.07', 'edges.putOmissionDeletesInbound', 'Does a PUT that omits an edge delete it, seen from the other end?',
            hasEdge(getB2.body as Map, 'SourceID', A) ? 'No: B still shows SourceID A.' : 'Yes: B no longer shows SourceID A.',
            'observed', "PUT A ${putA.status}.", [putA, getB2])

        def putB = putProbe(probeDoc(B, [[SourceID: A, type: EDGE_TYPE]]))
        def getA4 = http.get(entityPath(A))
        add('WS0.07', 'edges.inboundOnPut', 'Does an inbound edge asserted in a PUT link the entities?',
            hasEdge(getA4.body as Map, 'TargetID', B) ? 'Yes: A shows the outbound edge again.' : 'No.',
            'observed', "PUT B ${putB.status}.", [putB, getA4])

        def putAC = putProbe(probeDoc(A, [toB, [TargetID: C, type: EDGE_TYPE]]))
        def getA5 = http.get(entityPath(A))
        add('WS0.07', 'edges.putToMissing', 'What happens to an edge to a missing entity on PUT?',
            !putAC.success ? "Rejected: ${putAC.status}, ${shape(putAC)}." : (hasEdge(getA5.body as Map, 'TargetID', C) ? 'Kept dangling: the edge to the missing entity is stored.' : 'Dropped silently: PUT succeeded and only the edge to the existing entity remains.'),
            'observed', null, [putAC, getA5])

        String lu = contract.lastUpdatedField
        Map latestA = getA5.body instanceof Map ? getA5.body as Map : null
        if (contract.timestampParam && lu && latestA?.get(lu) != null) {
            Object L = latestA[lu]
            String at = L as String
            String before = L instanceof Number ? ((L as Number).longValue() - 1000L) as String :
                DateTimeFormatter.ISO_INSTANT.format(Instant.parse(at).minusSeconds(1))
            boolean inAt = enumerate(at, 200, opts.maxPages).any { it.ID == A }
            boolean inBefore = enumerate(before, 200, opts.maxPages).any { it.ID == A }
            add('WS0.02', 'search.boundary', 'Is the timestamp filter strictly-after or inclusive?',
                !inBefore ? 'Cannot tell: the entity was not found even one second earlier.' : (inAt ? 'Inclusive: searching from exactly the entity\'s timestamp returns it.' : 'Strictly after: searching from exactly the entity\'s timestamp does not return it.'),
                inBefore ? 'observed' : 'inconclusive', "Used `${lu}` = ${at}. If the service stores sub-second precision but returns seconds, re-check with a sub-second value.", [])
            add('WS0.02', 'search.returnsUpdated', 'Does the timestamp search return entities that were updated, not only created?',
                inBefore ? 'Yes: A was found after its PUTs.' : 'Not observed.', inBefore ? 'observed' : 'inconclusive')
            def putTs = putProbe(probeDoc(A, [toB]) + [(lu): L instanceof Number ? 946684800000L : '2000-01-01T00:00:00Z'])
            def getA6 = http.get(entityPath(A))
            Object after = (getA6.body as Map)?.get(lu)
            add('WS0.06', 'document.lastUpdatedOnPut', "What happens when a PUT carries `${lu}`?",
                !putTs.success ? "Rejected: ${putTs.status}, ${shape(putTs)}. The connector must never send it." :
                    (after as String)?.startsWith('2000') || after == 946684800000L ? 'Accepted and stored. The connector must never send it, or recon will chase its own writes.' : 'Ignored: the service set its own value.',
                'observed', null, [putTs, getA6])
        } else {
            add('WS0.02', 'search.boundary', 'Is the timestamp filter strictly-after or inclusive?', 'Not tested: no timestamp parameter or last-updated field was found.', 'not-tested')
        }

        def delB = http.delete(entityPath(B))
        def getA7 = http.get(entityPath(A))
        def getB3 = http.get(entityPath(B))
        add('WS0.07', 'edges.deleteCascades', 'Does DELETE remove the edges touching the entity?',
            hasEdge(getA7.body as Map, 'TargetID', B) ? 'No: A still shows an edge to the deleted entity.' : 'Yes: A no longer shows the edge.',
            'observed', "DELETE ${delB.status}; GET of the deleted entity then returns ${getB3.status}.", [delB, getA7, getB3])
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
        def lower = http.get(entityPath(mixed.toLowerCase()))
        touched << mixed.toLowerCase()
        add('WS0.09', 'ids.caseSensitivity', 'Are IDs case-sensitive?',
            !c.success ? "Could not create the test entity: ${c.status}." : (lower.status == 404 ? 'Yes: the lower-cased ID is not found.' : (lower.success ? 'No: the lower-cased ID finds the entity.' : "Unclear: ${lower.status}.")),
            c.success ? 'observed' : 'error', null, [c, lower])
    }

    private void idRoundTrip(String label, String id) {
        def c = createProbe(probeDoc(id))
        if (!c.success) {
            add('WS0.09', "ids.${label}", "Is an ID with ${label} accepted?", "Rejected on create: ${c.status}, ${shape(c)}.", 'observed', null, [c])
            return
        }
        def g = http.get(entityPath(id))
        String back = (g.body as Map)?.ID
        def d = http.delete(entityPath(id))
        def g2 = http.get(entityPath(id))
        add('WS0.09', "ids.${label}", "Is an ID with ${label} accepted and addressable when percent-encoded?",
            g.success && back == id ? (d.success && g2.status == 404 ? 'Yes: created, read back unchanged and deleted.' : "Created and read back, but delete returned ${d.status} and a later GET ${g2.status}.") :
                (g.success ? "Created, but read back as a different ID (${scrubber.isProbeId(back) ? back : 'other'})." : "Created, but GET by the encoded ID returned ${g.status}."),
            'observed', "Path used: ${entityPath(id).take(120)}", [c, g, d, g2])
    }

    // ------------------------------------------------------------------ WS0.03 stability under change (writes)

    void checkPagingStability() {
        String lu = contract.lastUpdatedField
        if (!(contract.timestampParam && contract.pageSizeParam && (contract.offsetParam || contract.cursorParam) && lu)) {
            add('WS0.03', 'paging.updateDuringEnumeration', 'Can an entity updated mid-enumeration be skipped or returned twice?',
                'Not tested: the timestamp parameter, page size, paging parameter and last-updated field must all be known first.', 'not-tested')
            return
        }
        List<String> stab = (1..4).collect { pid("stab-${it}") }
        List<Map> docs = []
        stab.each { createProbe(probeDoc(it)); def d = getDoc(it); if (d) docs << d }
        if (docs.size() < 4) {
            add('WS0.03', 'paging.updateDuringEnumeration', 'Can an entity updated mid-enumeration be skipped or returned twice?', 'Could not create the test entities.', 'error')
            return
        }
        Object earliest = docs.collect { it[lu] }.min { a, b -> (a as String) <=> (b as String) }
        String since = earliest instanceof Number ? ((earliest as Number).longValue() - 1000L) as String :
            DateTimeFormatter.ISO_INSTANT.format(Instant.parse(earliest as String).minusSeconds(1))
        Map<String, Integer> seen = stab.collectEntries { [(it): 0] }
        boolean touchedOne = false
        enumerate(since, 1, 50) { List<Map> page ->
            page.each { if (seen.containsKey(it.ID)) seen[it.ID as String]++ }
            def hit = page.find { seen.containsKey(it.ID) }
            if (hit && !touchedOne) {
                touchedOne = true
                putProbe(probeDoc(hit.ID as String, [], [probe: 'touched']))
            }
        }
        def dups = seen.findAll { k, v -> v > 1 }.keySet()
        def skipped = seen.findAll { k, v -> v == 0 }.keySet()
        add('WS0.03', 'paging.updateDuringEnumeration', 'Can an entity updated mid-enumeration be skipped or returned twice?',
            skipped ? "Skipped: ${skipped.size()} of 4 entities never appeared. Recon could miss entities; enumeration needs a stable order." :
                (dups ? 'Returned twice: the updated entity reappeared later. Harmless for recon.' : 'Neither: each entity appeared exactly once.'),
            'observed', 'Page size 1, one entity updated after the first page that contained a test entity.')
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

    void cleanup() {
        http.currentCheck = 'cleanup'
        touched.toList().reverse().each { id ->
            def d = http.delete(entityPath(id))
            if (!(d.success || d.status == 404)) cleanupProblems << "DELETE ${id} returned ${d.status}".toString()
        }
        touched.each { id ->
            def g = http.get(entityPath(id))
            if (g.success) cleanupProblems << "${id} still exists after cleanup".toString()
        }
    }
}
