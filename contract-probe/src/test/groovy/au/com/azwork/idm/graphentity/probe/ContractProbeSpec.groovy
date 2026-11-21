package au.com.azwork.idm.graphentity.probe

import au.com.azwork.idm.graphentity.http.HttpResponse
import au.com.azwork.idm.graphentity.support.FakeGraphHttpServer
import au.com.azwork.idm.graphentity.support.FakeGraphService
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Runs the contract probe against {@link FakeGraphHttpServer}, which implements the service's documented
 * behaviour plus the design's assumptions (FakeGraphService lists which is which). The probe must report exactly
 * those semantics, so when it is pointed at the real service, any difference in its report is a real difference.
 */
class ContractProbeSpec extends Specification {

    @TempDir File dir
    FakeGraphService service = new FakeGraphService()
    FakeGraphHttpServer server

    /** Foreign (non-probe) data. IDs and values carry a PII- marker so leaks are easy to detect. */
    def setup() {
        seedForeign(service)
        server = new FakeGraphHttpServer(service).start()
    }

    static void seedForeign(FakeGraphService s) {
        s.seed([id: 'PII-Sanne', baseType: 4, attributes: [name: 'PII-Sanne Groen'], directArchetypes: ['Customer']])
        s.seed([id: 'PII-Account-1', baseType: 2, attributes: [name: 'PII-Current'], directArchetypes: ['Account']])
        s.seed([id: 'PII-Shared-S', baseType: 4, attributes: [:], directArchetypes: ['Customer', 'Shared']])
        s.seed([id: 'PII-Shared-R', baseType: 2, attributes: [:], directArchetypes: ['Account', 'Shared']])
        s.seedEdge('PII-Sanne', 'PII-Account-1', 'Owns')
        s.seedManifest('banking', ['Customer'], ['Account', 'Card'])
    }

    def cleanup() { server?.close() }

    ProbeOptions options(Map overrides = [:]) {
        def o = new ProbeOptions(baseUrl: server.baseUrl, apiKey: FakeGraphHttpServer.DEFAULT_API_KEY.toCharArray(),
            runId: 't1', outDir: dir, maxPages: 5, timeoutSeconds: 5, settleSeconds: 0)
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
        [entities: service.entities.keySet().collectEntries { [(it): service.renderEntity(it) + [archetypes: service.entities[it].directArchetypes]] },
         edges: new HashSet(service.edges)]
    }

    // ---------------------------------------------------------------- read-only mode

    def "read-only mode issues only GETs and search POSTs, and changes nothing"() {
        given:
        def before = snapshot()

        when:
        def p = probe()

        then:
        p.http.exchanges.every { it.method == 'GET' || (it.method == 'POST' && it.path == '/admin/entities/search') }
        p.http.exchanges.any { it.method == 'POST' }
        service.requests*.op.unique().sort() == ['GET', 'MANIFESTS', 'SEARCH']
        snapshot() == before
    }

    def "a search path that is the create endpoint is refused, so read-only mode cannot create entities"() {
        given:
        def before = snapshot()

        when:
        def p = probe(searchPath: '/admin/entities')

        then:
        f(p, 'search.path').confidence == 'error'
        f(p, 'search.path').answer.contains('creates entities')
        !p.http.exchanges.any { it.method == 'POST' }
        snapshot() == before
    }

    def "a 405 response is described with its Allow header"() {
        expect:
        ContractProbe.shape(new Exchange(status: 405, headers: [allow: 'POST'])) == 'no content type, empty body; Allow: POST'
    }

    def "write checks are reported as not tested in read-only mode"() {
        when:
        def p = probe()

        then:
        ['create.shape', 'create.relationshipShape', 'search.reflectsDelete', 'baseType.create', 'search.createdVisible', 'create.duplicate',
         'edges', 'attributes.omission', 'archetypes.replace', 'ids', 'paging.updateDuringEnumeration'].every { f(p, it).confidence == 'not-tested' }
    }

    def "the probe writes the service's write model: lowercase id, numeric base type, string attributes, sourceID/targetID (P17, P18, P20)"() {
        given:
        def probeDoc = ContractProbe.probeDoc('x', [ContractProbe.edge('x', 'y')], [a: 'b'], 4, ['Card'])

        expect:
        probeDoc == [id: 'x', baseType: 4, attributes: [probe: 'ws0', a: 'b'],
                     relationships: [[sourceID: 'x', targetID: 'y', type: 'Ws0ProbeEdge']], directArchetypes: ['Card']]
    }

    def "WS0.10: reports how a missing, wrong and valid API key are answered"() {
        when:
        def a = f(probe(), 'auth.status')

        then:
        a.answer == 'No key: 401. Wrong key: 401. Valid key: 404.'
        a.question.contains('`X-API-KEY`')
        a.detail.contains('does not exist')
        a.evidence.size() == 3
    }

    def "WS0.10: the key header name is configurable end to end"() {
        given:
        server.close()
        server = new FakeGraphHttpServer(service, 'other-key', 'X-Custom-Key').start()

        when:
        def p = probe(apiKey: 'other-key'.toCharArray(), apiKeyHeader: 'X-Custom-Key')

        then:
        f(p, 'auth.status').answer == 'No key: 401. Wrong key: 401. Valid key: 404.'
        f(p, 'search.path').confidence == 'observed'
    }

    def "WS0.02: finds the search path and the field that filters on the ID (P21)"() {
        when:
        def p = probe()

        then:
        f(p, 'search.path').answer == '`POST /admin/entities/search` with a JSON body. `{}` returned 4 entities.'
        f(p, 'search.idFilter').answer == '`ID`: an EQUALS filter on it returns exactly the entity.'
        p.contract.idFilterField == 'ID'
    }

    def "WS0.15: read-only, the numeric objectClass values show which base type each number means (P18, C6)"() {
        when:
        def p = probe()

        then:
        f(p, 'baseType.objectClass').answer == '4 = Subject, 2 = Resource, as P18 assumes.'
        f(p, 'baseType.objectClass').detail.contains('`4` → Subject ×2')
        p.contract.baseTypeNumbering == 'P18 (4 = Subject, 2 = Resource)'
    }

    def "WS0.15: a service whose numbering is reversed is reported as contradicting P18"() {
        given:
        server.close()
        def reversed = new FakeGraphService() {
            @Override Map<String, Object> renderSearchItem(String id) {
                def item = super.renderSearchItem(id)
                item + [baseType: item.baseType == 'Subject' ? 'Resource' : 'Subject']
            }
        }
        seedForeign(reversed)
        server = new FakeGraphHttpServer(reversed).start()

        when:
        def p = probe()

        then:
        f(p, 'baseType.objectClass').answer.startsWith('4 = Resource, 2 = Subject: this contradicts P18')
    }

    def "WS0.03: follows the documented paging protocol: pageSize and pagedResultsCookie"() {
        when:
        def p = probe()

        then:
        f(p, 'paging.envelope').answer == 'object, items under `results`; total count: `totalResults`; next-page cursor: `pagedResultsCookie`; `hasMore`: present.'
        f(p, 'paging.params').answer == 'Yes: `pageSize` is honoured, and sending back `pagedResultsCookie` returns the next page.'
        p.contract.pageSizeParam == 'pageSize'
        p.contract.cursorParam == 'pagedResultsCookie'
        f(p, 'paging.ordering').answer == 'Same first page twice: identical order. Order: sorted by ID.'
    }

    def "WS0.03: an empty service gives an inconclusive paging answer rather than a wrong one"() {
        given:
        service.entities.clear(); service.edges.clear()

        when:
        def p = probe()

        then:
        f(p, 'paging.params').confidence == 'inconclusive'
        f(p, 'search.idFilter').confidence == 'inconclusive'
        f(p, 'baseType.objectClass').confidence == 'inconclusive'
    }

    def "WS0.06: compares search items and the read model with the documented fields, and reports lastEvaluated's format"() {
        when:
        def p = probe()

        then:
        f(p, 'document.searchFields').answer == 'attributes, baseType, directArchetypes, isArchetypes, lastEvaluated, name, uid (as documented).'
        f(p, 'document.readModelFields').answer == 'Attributes, baseType, compoundAttributes, id, lastEvaluated, reconciliationStarted, relationships (as documented).'
        f(p, 'document.lastEvaluated').answer == 'ISO-8601, no fraction, Z'
        f(p, 'document.edgeKeys').answer == 'source, sourceType, target, type (as documented)'
    }

    def "WS0.06: GET by ID is tried on an entity that exists, which the first run never did (C1)"() {
        when:
        def p = probe()

        then:
        f(p, 'get.existingEntity').answer == 'Yes: 200, the read model.'
        p.contract.getById == 'works'
    }

    def "WS0.08: counts base types and flags archetypes shared across base types"() {
        when:
        def p = probe()

        then:
        f(p, 'types.baseTypes').answer == 'Resource: 2, Subject: 2'
        p.contract.ambiguousArchetypes == ['Shared']
        f(p, 'types.archetypes').answer.contains('Shared')
    }

    def "WS0.15: lists the archetypes the manifests define, per base type (P22)"() {
        when:
        def p = probe()

        then:
        f(p, 'manifests.archetypes').answer == '1 manifest(s). Subject archetypes: Customer. Resource archetypes: Account, Card.'
        p.contract.resourceArchetypes == ['Account', 'Card']
    }

    def "WS0.15: a service with no manifests is reported, not treated as a failure"() {
        given:
        service.manifests.clear()

        when:
        def p = probe()

        then:
        f(p, 'manifests.archetypes').answer.startsWith('404')
        f(p, 'manifests.archetypes').confidence == 'observed'
    }

    def "WS0.05: records the shape of read-only error responses"() {
        when:
        def p = probe()

        then:
        f(p, 'errors.getMissing').answer == '404: application/problem+json, JSON object with keys [status, title, traceId, type]'
        f(p, 'errors.badFilter').answer.startsWith('400: application/problem+json')
        f(p, 'errors.badObjectClass').answer.startsWith('200')   // the fake treats an unknown number as an empty base type
    }

    // ---------------------------------------------------------------- write mode

    def "WS0.15: an entity created with base type 4 is a Subject, and is visible to search (P18, C5)"() {
        when:
        def p = probe(allowWrites: true)

        then:
        f(p, 'baseType.create').answer == 'Yes: created with 4 it is a `Subject`, and created with 2 a `Resource`; a search with objectClass 4 finds it.'
        f(p, 'search.createdVisible').answer.startsWith('Yes')
        p.contract.baseTypeOnCreate == 'confirmed: 4 = Subject'
    }

    def "WS0.15: each create variant is tried once, and the documented EntityDTO is adopted when it is accepted"() {
        when:
        def p = probe(allowWrites: true)

        then:
        f(p, 'create.shape').answer == 'The documented EntityDTO is accepted.'
        f(p, 'create.shape').detail.contains('1. documented EntityDTO → 200')
        f(p, 'create.shape').detail.contains('7. design casing (ID, BaseType, Relationships) → 400')
        p.contract.createShape == 'documented EntityDTO'
        service.requests.findAll { it.op == 'POST' && (it.body?.id ?: it.body?.ID)?.contains('-shape-') }.size() == ContractProbe.CREATE_VARIANTS.size()
    }

    def "WS0.15: when the service needs attributes.ID, that variant is adopted and every later write carries it"() {
        given:
        server.close()
        def picky = new FakeGraphService() {
            @Override HttpResponse create(Map<String, Object> d) { (d.attributes as Map)?.ID == d.id ? super.create(d) : new HttpResponse(status: 500) }
            @Override HttpResponse replace(String id, Map<String, Object> d) { (d.attributes as Map)?.ID == d.id ? super.replace(id, d) : new HttpResponse(status: 500) }
        }
        seedForeign(picky)
        server = new FakeGraphHttpServer(picky).start()

        when:
        def p = probe(allowWrites: true)

        then:
        f(p, 'create.shape').answer.contains('the first variant that is: EntityDTO + attributes.ID')
        f(p, 'baseType.create').answer.startsWith('Yes')
        f(p, 'attributes.omission').answer.startsWith('Yes')
        picky.requests.findAll { it.op in ['POST', 'PUT'] && !(it.body?.id as String)?.contains('-shape-') && it.body?.id }
            .every { (it.body.attributes as Map).ID == it.body.id || (it.body.id as String).contains('non-string') }
        p.cleanupProblems == []
    }

    def "WS0.15: when no create variant is accepted, the other write checks are skipped with the reason, and cleanup asks for no proof"() {
        given:
        server.close()
        def closed = new FakeGraphService() {
            @Override HttpResponse create(Map<String, Object> d) { requests << [op: 'POST', body: d]; new HttpResponse(status: 500) }
        }
        seedForeign(closed)
        server = new FakeGraphHttpServer(closed).start()

        when:
        def p = probe(allowWrites: true)

        then:
        f(p, 'create.shape').confidence == 'error'
        ['baseType.create', 'edges', 'attributes.omission', 'ids', 'search.reflectsDelete'].every {
            f(p, it).confidence == 'not-tested' && f(p, it).answer.contains('no create variant was accepted')
        }
        p.written.empty
        p.cleanupProblems == []
        p.cleanupUnconfirmed == []
    }

    def "WS0.15: reports which relationship form a create accepts (P17)"() {
        expect:
        f(probe(allowWrites: true), 'create.relationshipShape').answer == 'P17 `{sourceID, targetID, type}` → 200; design `{TargetID, type}` → 400.'
    }

    def "WS0.02: search stops returning a deleted entity in the fake"() {
        when:
        def p = probe(allowWrites: true)

        then:
        f(p, 'search.reflectsDelete').answer.startsWith('Yes')
        p.contract.searchReflectsDelete == true
    }

    def "WS0.02: when search still returns a deleted entity, a later search miss is not taken as proof of removal"() {
        given:
        server.close()
        def lagging = new FakeGraphService() {
            Map<String, Integer> ghosts = [:]
            @Override HttpResponse get(String id) { requests << [op: 'GET', id: id]; new HttpResponse(status: 500) }
            @Override HttpResponse delete(String id) {
                def r = super.delete(id)
                if (r.status == 200 && id.endsWith('delete-check')) ghosts[id] = 1
                r
            }
            @Override HttpResponse search(Map<String, Object> q) {
                def r = super.search(q)
                String id = (q.filter as Map)?.right
                if ((q.filter as Map)?.operation == 'EQUALS' && (ghosts[id] ?: 0) > 0) {
                    ghosts[id]--
                    (r.body.results as List) << [uid: id, name: id, baseType: 'Resource', attributes: [:], isArchetypes: [], directArchetypes: [], lastEvaluated: '2026-01-01T00:00:00Z']
                }
                r
            }
        }
        seedForeign(lagging)
        server = new FakeGraphHttpServer(lagging).start()

        when:
        def p = probe(allowWrites: true)
        ProbeReport.write(p, dir)

        then:
        f(p, 'search.reflectsDelete').answer.startsWith('No: after DELETE (200) a search still finds it')
        p.cleanupProblems == []
        p.cleanupUnconfirmed
        p.cleanupUnconfirmed.every { it in p.written }
        new File(dir, 'report.md').text.contains('**Removal not confirmed**')
        !new File(dir, 'report.md').text.contains('confirmed gone')
    }

    def "WS0.15: entities left by earlier probe runs are listed, and kept out of the type statistics"() {
        given:
        service.seed([id: 'ws0-probe-20250101-000000-seed-1', baseType: 2, attributes: [probe: 'ws0'], isArchetypes: ['Ws0Probe']])
        service.seed([id: 'ws0-probe-20250101-000000-seed-2', baseType: 2, attributes: [probe: 'ws0'], isArchetypes: ['Ws0Probe']])

        when:
        def p = probe()

        then:
        f(p, 'probe.leftovers').answer == 'Yes: 2, from run(s) 20250101-000000 ×2. Remove them by hand; their IDs are in the detail.'
        f(p, 'probe.leftovers').detail == 'ws0-probe-20250101-000000-seed-1, ws0-probe-20250101-000000-seed-2'
        f(p, 'types.baseTypes').answer == 'Resource: 2, Subject: 2'
        !f(p, 'types.archetypes').detail.contains('Ws0Probe')
    }

    def "WS0.15: a manifests response that is not the documented list is described by its outline, without values"() {
        given:
        server.close()
        def odd = new FakeGraphService() {
            @Override HttpResponse manifests() {
                new HttpResponse(status: 200, headers: ['Content-Type': 'application/json'], body: [paths: [[name: 'PII-x', count: 2, rules: [a: 'PII-y']]]])
            }
        }
        seedForeign(odd)
        server = new FakeGraphHttpServer(odd).start()

        when:
        def a = f(probe(), 'manifests.archetypes')

        then:
        a.answer == 'Cannot tell: 200, but not the documented list of manifests. Outline: {paths: list of 1 × {name: string, count: number, rules: object (1 fields)}}.'
        !a.answer.contains('PII-')
    }

    def "WS0.07/WS0.04: write checks observe every relationship semantic, including Q07.8"() {
        when:
        def p = probe(allowWrites: true)

        then:
        f(p, 'edges.postToMissing').answer.startsWith('Dropped silently')
        f(p, 'create.duplicate').answer.startsWith('409')
        f(p, 'edges.inboundOnCreate').answer.startsWith('Yes')
        f(p, 'edges.getShowsInbound').answer.startsWith('Yes')
        f(p, 'edges.readShape').answer == 'source, sourceType, target, type'
        f(p, 'edges.sourceOmission').answer.startsWith('Yes')
        f(p, 'edges.inboundOnPut').answer.startsWith('Yes')
        f(p, 'edges.targetOmission').answer.startsWith('Yes')   // the fake assumes both directions (Q07.8)
        f(p, 'edges.putToMissing').answer.startsWith('Dropped silently')
        f(p, 'edges.relationshipEndpoints').answer.startsWith('DELETE 200: removed. POST 200: added back.')
        f(p, 'edges.deleteCascades').answer.startsWith('Yes')
    }

    def "WS0.07: every relationship the probe writes is in the P17 shape and names the entity written"() {
        when:
        probe(allowWrites: true)

        then:
        // rel-design deliberately sends the design's one-sided form, to see whether the service accepts it.
        def bodies = service.requests.findAll { it.op in ['POST', 'PUT'] && it.body?.relationships && !(it.body.id as String).endsWith('rel-design') }*.body
        bodies
        bodies.every { b -> b.relationships.every { r -> r.keySet() == ['sourceID', 'targetID', 'type'] as Set && b.id in [r.sourceID, r.targetID] } }
    }

    def "WS0.15: an update that omits an attribute removes it, and one that lists fewer archetypes replaces them (P20, P22)"() {
        when:
        def p = probe(allowWrites: true)

        then:
        f(p, 'attributes.omission').answer == 'Yes: the omitted attribute is gone, and `keep` was updated.'
        f(p, 'archetypes.replace').answer == 'Yes: Created with both; after an update listing only `Card`, only `Card` remains.'
    }

    def "WS0.15: a service that merges attributes and archetypes is reported as such"() {
        given:
        server.close()
        def merging = new FakeGraphService() {
            @Override HttpResponse replace(String id, Map<String, Object> document) {
                def old = entities[id]
                if (old == null) return super.replace(id, document)
                super.replace(id, document + [attributes: (old.attributes as Map) + (document.attributes as Map),
                                              directArchetypes: ((old.directArchetypes as List) + (document.directArchetypes as List)).unique()])
            }
        }
        seedForeign(merging)
        server = new FakeGraphHttpServer(merging).start()

        when:
        def p = probe(allowWrites: true)

        then:
        f(p, 'attributes.omission').answer.startsWith('No: the omitted attribute is still there')
        f(p, 'archetypes.replace').answer.startsWith('No:')
        f(p, 'archetypes.replace').answer.contains('merges')
    }

    def "WS0.15: --archetypes overrides the manifests, and without either the archetype check is not tested"() {
        when:
        def given = probe(allowWrites: true, archetypes: ['X1', 'X2'])
        service.manifests.clear()
        def none = probe(allowWrites: true, runId: 't2')

        then:
        f(given, 'archetypes.replace').answer.contains('only `X2` remains')
        f(none, 'archetypes.replace').confidence == 'not-tested'
    }

    def "WS0.05: write checks record error responses for bad writes"() {
        when:
        def p = probe(allowWrites: true)

        then:
        f(p, 'errors.postWithoutId').answer.startsWith('400')
        f(p, 'errors.malformedJson').answer.startsWith('400')
        f(p, 'errors.nonStringAttribute').answer.startsWith('400')
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
        p.cleanupConfirmedBy.values().every { it == 'GET 404' }
        !service.entities.keySet().any { it.startsWith(p.opts.prefix) }
        !service.edges.any { it.any { id -> id.startsWith(p.opts.prefix) } }
        snapshot() == before
    }

    def "writes are only ever addressed to probe-prefixed IDs"() {
        when:
        def p = probe(allowWrites: true)

        then:
        def writes = service.requests.findAll { it.op in ['POST', 'PUT', 'DELETE', 'ADD_RELATIONSHIP', 'DELETE_RELATIONSHIP'] }
        writes
        writes.every { w ->
            String target = w.id ?: w.source ?: w.body?.id
            target == null ? w.op == 'POST' : target.startsWith(p.opts.prefix)
        }
    }

    def "when GET fails and no search can prove absence, cleanup says so instead of claiming success"() {
        given:
        server.close()
        def blind = new FakeGraphService() {
            @Override HttpResponse get(String id) { requests << [op: 'GET', id: id]; new HttpResponse(status: 500) }
            @Override HttpResponse search(Map<String, Object> query) {
                query.filter ? new HttpResponse(status: 400, body: [title: 'no filters', status: 400]) : super.search(query)
            }
        }
        seedForeign(blind)
        (1..250).each { blind.seed([id: "PII-Bulk-${it}".toString(), baseType: 2, attributes: [:]]) }
        server = new FakeGraphHttpServer(blind).start()

        when:
        def p = probe(allowWrites: true, maxPages: 1)

        then:
        p.contract.idFilterField == null
        p.cleanupProblems
        p.cleanupProblems.every { it.startsWith('could not confirm') }
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

    def "a rejected API key stops the probe after the auth check"() {
        when:
        def p = probe(apiKey: 'wrong-key'.toCharArray(), allowWrites: true)

        then:
        f(p, 'auth.valid').answer.startsWith('No: 401')
        p.http.exchanges.size() == 3
    }
}
