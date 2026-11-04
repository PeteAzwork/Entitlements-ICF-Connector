package au.com.azwork.idm.graphentity.probe

import au.com.azwork.idm.graphentity.support.FakeGraphHttpServer
import au.com.azwork.idm.graphentity.support.FakeGraphService
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Runs the contract probe against {@link FakeGraphHttpServer}, which implements the design's documented
 * service semantics (§2). The probe must report exactly those semantics, so when it is pointed at the real
 * service, any difference in its report is a real difference in the service.
 */
class ContractProbeSpec extends Specification {

    @TempDir File dir
    FakeGraphService service = new FakeGraphService()
    FakeGraphHttpServer server

    /** Foreign (non-probe) data. IDs and values carry a PII- marker so leaks are easy to detect. */
    def setup() {
        service.seed([ID: 'PII-Sanne', BaseType: 4, attributes: [ID: 'PII-Sanne', name: 'PII-Sanne Groen'], directArchetypes: ['Customer']])
        service.seed([ID: 'PII-Account-1', BaseType: 2, attributes: [ID: 'PII-Account-1', name: 'PII-Current'], directArchetypes: ['Account']])
        service.seed([ID: 'PII-Shared-S', BaseType: 4, attributes: [ID: 'PII-Shared-S'], directArchetypes: ['Customer', 'Shared']])
        service.seed([ID: 'PII-Shared-R', BaseType: 2, attributes: [ID: 'PII-Shared-R'], directArchetypes: ['Account', 'Shared']])
        service.seedEdge('PII-Sanne', 'PII-Account-1', 'Owns')
        server = new FakeGraphHttpServer(service).start()
    }

    def cleanup() { server?.close() }

    ProbeOptions options(Map overrides = [:]) {
        def o = new ProbeOptions(baseUrl: server.baseUrl, username: 'svc-idm-sync', password: 'secret'.toCharArray(),
            runId: 't1', outDir: dir, maxPages: 5, timeoutSeconds: 5)
        overrides.each { k, v -> o."$k" = v }
        o
    }

    ContractProbe probe(Map overrides = [:]) { new ContractProbe(options(overrides)).run() }

    static Finding f(ContractProbe p, String key) {
        def found = p.findings.find { it.key == key }
        assert found, "no finding ${key}; have ${p.findings*.key}"
        found
    }

    Map snapshot() {
        [entities: service.entities.keySet().collectEntries { [(it): service.render(it)] }, edges: new HashSet(service.edges)]
    }

    // ---------------------------------------------------------------- read-only mode

    def "read-only mode issues only GET requests and changes nothing"() {
        given:
        def before = snapshot()

        when:
        def p = probe()

        then:
        p.http.exchanges*.method.unique() == ['GET']
        service.requests*.op.unique().sort() == ['GET', 'SEARCH']
        snapshot() == before
    }

    def "write checks are reported as not tested in read-only mode"() {
        when:
        def p = probe()

        then:
        ['create.duplicate', 'edges', 'ids', 'search.boundary', 'paging.updateDuringEnumeration'].every { f(p, it).confidence == 'not-tested' }
    }

    def "WS0.10: reports how missing, wrong and valid credentials are answered"() {
        when:
        def a = f(probe(), 'auth.status')

        then:
        a.answer.contains('No credentials: 401')
        a.answer.contains('Basic realm')
        a.answer.contains('Wrong credentials: 401')
        a.answer.contains('Valid credentials: 200')
        a.evidence.size() == 3
    }

    def "WS0.02: finds the search path, the timestamp parameter and the accepted datetime formats"() {
        when:
        def p = probe()

        then:
        f(p, 'search.path').answer == '`/admin/entities`'
        f(p, 'search.timestampParam').answer == '`updatedAfter`'
        p.contract.timestampParam == 'updatedAfter'
        def formats = p.contract.acceptedDatetimeFormats as List
        formats.containsAll(['ISO-8601, seconds, Z', 'ISO-8601, milliseconds, Z'])
        !formats.contains('epoch milliseconds')
        !formats.contains('date only')
    }

    def "WS0.03: finds the envelope, page-size and offset parameters, and the ordering"() {
        when:
        def p = probe()

        then:
        f(p, 'paging.envelope').answer.startsWith('object, items under `items`; total count: `totalCount`; next-page cursor: none')
        p.contract.pageSizeParam == 'pageSize'
        p.contract.offsetParam == 'offset'
        p.contract.cursorParam == null
        f(p, 'paging.params').answer == 'offset via `offset`'
        f(p, 'paging.ordering').answer == 'Same first page twice: identical order. Order: sorted by ID.'
    }

    def "WS0.03: an empty service gives an inconclusive paging answer rather than a wrong one"() {
        given:
        service.entities.clear(); service.edges.clear()

        when:
        def p = probe()

        then:
        f(p, 'paging.params').confidence == 'inconclusive'
        f(p, 'search.timestampParam').confidence == 'inconclusive'
    }

    def "WS0.06: identifies the last-updated field and its format, and compares search with GET"() {
        when:
        def p = probe()

        then:
        p.contract.lastUpdatedField == 'lastUpdated'
        f(p, 'document.lastUpdatedField').answer == '`lastUpdated`, ISO-8601, no fraction, Z'
        f(p, 'document.searchVsGet').answer == 'Yes, the same top-level fields.'
        f(p, 'document.edgeKeys').answer == 'SourceID, type'
    }

    def "WS0.08: counts BaseType values and flags archetypes shared across BaseTypes"() {
        when:
        def p = probe()

        then:
        f(p, 'types.baseTypes').answer == '2: 2, 4: 2'
        p.contract.ambiguousArchetypes == ['Shared']
        f(p, 'types.archetypes').answer.contains('Shared')
    }

    def "WS0.05: records the shape of read-only error responses"() {
        when:
        def p = probe()

        then:
        f(p, 'errors.getMissing').answer == '404: no content type, empty body'
        f(p, 'errors.badTimestamp').answer == "400: application/json, JSON object with keys [error]"
    }

    // ---------------------------------------------------------------- write mode

    def "WS0.07/WS0.04/WS0.06: write checks observe every documented edge semantic"() {
        when:
        def p = probe(allowWrites: true)

        then:
        f(p, 'edges.postToMissing').answer.startsWith('Dropped silently')
        f(p, 'create.duplicate').answer.startsWith('409')
        f(p, 'edges.inboundOnCreate').answer.startsWith('Yes')
        f(p, 'edges.getShowsInbound').answer.startsWith('Yes')
        f(p, 'edges.putOmissionDeletesInbound').answer.startsWith('Yes')
        f(p, 'edges.inboundOnPut').answer.startsWith('Yes')
        f(p, 'edges.putToMissing').answer.startsWith('Dropped silently')
        f(p, 'edges.deleteCascades').answer.startsWith('Yes')
        f(p, 'document.lastUpdatedOnPut').answer.startsWith('Ignored')
    }

    def "WS0.02: write checks find that the timestamp filter is strictly after"() {
        when:
        def p = probe(allowWrites: true)

        then:
        f(p, 'search.boundary').answer.startsWith('Strictly after')
        f(p, 'search.returnsUpdated').answer.startsWith('Yes')
    }

    def "WS0.05: write checks record error responses for bad writes"() {
        when:
        def p = probe(allowWrites: true)

        then:
        f(p, 'errors.postWithoutId').answer.startsWith('400')
        f(p, 'errors.malformedJson').answer.startsWith('400')
        f(p, 'errors.putMissing').answer.startsWith('404')
        f(p, 'errors.deleteMissing').answer.startsWith('404')
    }

    def "WS0.09: awkward IDs round-trip when percent-encoded, and IDs are case-sensitive"() {
        when:
        def p = probe(allowWrites: true)

        then:
        ['space', 'slash', 'question', 'hash', 'plus', 'percent', 'unicode', 'dot', 'length 64', 'length 255', 'length 256', 'length 1024'].every {
            f(p, "ids.${it}").answer.startsWith('Yes')
        }
        f(p, 'ids.caseSensitivity').answer.startsWith('Yes')
    }

    def "WS0.03: an entity updated during enumeration is neither skipped nor duplicated by the fake"() {
        expect:
        f(probe(allowWrites: true), 'paging.updateDuringEnumeration').answer.startsWith('Neither')
    }

    def "write mode cleans up every probe entity and never touches foreign ones"() {
        given:
        def before = snapshot()

        when:
        def p = probe(allowWrites: true)

        then:
        p.cleanupProblems == []
        !service.entities.keySet().any { it.startsWith(p.opts.prefix) }
        !service.edges.any { it.any { id -> id.startsWith(p.opts.prefix) } }
        snapshot() == before
    }

    def "writes are only ever addressed to probe-prefixed IDs"() {
        when:
        def p = probe(allowWrites: true)

        then:
        def writes = service.requests.findAll { it.op in ['POST', 'PUT', 'DELETE'] }
        writes
        writes.every { (it.id ?: it.body?.ID ?: '').startsWith(p.opts.prefix) || it.body?.ID == null }
    }

    // ---------------------------------------------------------------- failure handling

    def "a transport failure is reported and stops the probe without throwing"() {
        given:
        def port = server.baseUrl.tokenize(':').last()
        server.close()

        when:
        def p = new ContractProbe(options(baseUrl: "http://127.0.0.1:${port}", allowWrites: true)).run()

        then:
        f(p, 'transport').confidence == 'error'
        p.findings*.key.containsAll(['auth.status', 'transport'])
        !p.findings*.key.contains('search.path')
    }

    def "rejected credentials stop the probe after the auth check"() {
        when:
        def p = probe(password: 'wrong'.toCharArray(), allowWrites: true)

        then:
        f(p, 'auth.valid').answer.startsWith('No: 401')
        p.http.exchanges.size() == 3
    }
}
