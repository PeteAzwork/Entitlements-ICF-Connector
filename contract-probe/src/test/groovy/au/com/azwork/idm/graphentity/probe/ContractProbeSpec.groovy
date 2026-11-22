package au.com.azwork.idm.entitlementsaccelerator.probe

import au.com.azwork.idm.entitlementsaccelerator.http.HttpResponse
import au.com.azwork.idm.entitlementsaccelerator.support.FakeGraphHttpServer
import au.com.azwork.idm.entitlementsaccelerator.support.FakeGraphService
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Runs the contract probe against {@link FakeGraphHttpServer}, which implements the service's documented
 * behaviour plus the design's assumptions (FakeGraphService lists which is which). The probe must report exactly
 * those semantics, so when it is pointed at the real service, any difference in its report is a real difference.
 */
class ContractProbeSpec extends Specification {

    /**
     * The service's timing as the real runs saw it (C1, C5): after each write, search shows the previous version
     * of the entity (or nothing) for the next {@code lag} searches on its ID, and GET of any entity the write touches
     * fails with 500 for the next {@code lag} reads.
     */
    static class LaggingService extends FakeGraphService {
        final int lag
        final Map<String, Integer> gets = [:]
        final Map<String, Map> stale = [:]

        LaggingService(int lag) { this.lag = lag }

        private void noteWrite(String id, Map doc, Map before) {
            stale[id] = [left: lag, item: before]
            ([id] + ((doc.relationships ?: []) as List).collectMany { Map r -> [r.sourceID, r.targetID] }).unique().each { gets[it as String] = lag }
        }

        @Override HttpResponse create(Map<String, Object> d) {
            Map before = d.id instanceof String && entities.containsKey(d.id) ? renderSearchItem(d.id as String) : null
            def r = super.create(d)
            if (r.status == 200) noteWrite(d.id as String, d, before)
            r
        }

        @Override HttpResponse replace(String id, Map<String, Object> d) {
            Map before = entities.containsKey(id) ? renderSearchItem(id) : null
            def r = super.replace(id, d)
            if (r.status == 200) noteWrite(id, d, before)
            r
        }

        @Override HttpResponse get(String id) {
            if ((gets[id] ?: 0) > 0) {
                gets[id] = gets[id] - 1
                requests << [op: 'GET', id: id]
                return new HttpResponse(status: 500)
            }
            super.get(id)
        }

        @Override HttpResponse search(Map<String, Object> q) {
            def r = super.search(q)
            Map f = q.filter as Map
            Map st = f?.operation == 'EQUALS' ? stale[f.right as String] : null
            if (r.status == 200 && st && (st.left as int) > 0) {
                st.left = (st.left as int) - 1
                List results = (r.body.results as List).findAll { it.uid != f.right }
                if (st.item) results << st.item
                r.body.results = results
            }
            r
        }
    }

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

    def "the probe writes the service's write model: lowercase id, attributes.ID, numeric base type, string attributes, sourceID/targetID (C8, P17, P18, P20)"() {
        given:
        def probeDoc = ContractProbe.probeDoc('x', [ContractProbe.edge('x', 'y')], [a: 'b'], 4, ['Card'])

        expect:
        probeDoc == [id: 'x', baseType: 4, attributes: [ID: 'x', probe: 'ws0', a: 'b'],
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

    def "WS0.15: each create variant is tried once, and the designed body (EntityDTO with attributes.ID) is adopted"() {
        when:
        def p = probe(allowWrites: true)

        then:
        f(p, 'create.shape').answer == 'The EntityDTO with attributes.ID is accepted, as designed (C8).'
        f(p, 'create.shape').detail.contains('1. EntityDTO with attributes.ID (C8) → 200')
        f(p, 'create.shape').detail.contains('2. EntityDTO without attributes.ID → 500')
        f(p, 'create.shape').detail.contains('6. design casing (ID, BaseType, Relationships) → 400')
        p.contract.createShape == 'EntityDTO with attributes.ID (C8)'
        service.requests.findAll { it.op == 'POST' && (it.body?.id ?: it.body?.ID)?.contains('-shape-') }.size() == ContractProbe.CREATE_VARIANTS.size()
    }

    def "WS0.15: if the service stops accepting the designed body, the next accepted variant is adopted and the change reported"() {
        given:
        server.close()
        def changed = new FakeGraphService() {
            @Override HttpResponse create(Map<String, Object> d) { d.directArchetypes ? super.create(d) : new HttpResponse(status: 500) }
            @Override HttpResponse replace(String id, Map<String, Object> d) { d.directArchetypes ? super.replace(id, d) : new HttpResponse(status: 500) }
        }
        seedForeign(changed)
        server = new FakeGraphHttpServer(changed).start()

        when:
        def p = probe(allowWrites: true)

        then:
        f(p, 'create.shape').answer.contains('the first variant that is: EntityDTO with attributes.ID and a direct archetype')
        f(p, 'create.shape').answer.contains('tell the service team')
        f(p, 'baseType.create').answer.startsWith('Yes')
        f(p, 'attributes.omission').answer.startsWith('No')   // merged, as the service does (C17)
        p.cleanupProblems == []
    }

    def "every write the probe sends carries attributes.ID equal to the id, apart from the variant that tests its absence (C8)"() {
        when:
        probe(allowWrites: true)

        then:
        def writes = service.requests.findAll { it.op in ['POST', 'PUT'] && it.body?.id }
        writes
        writes.every { (it.body.attributes as Map).ID == it.body.id || (it.body.id as String).endsWith('shape-2') }
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
        f(p, 'search.reflectsDelete').answer.startsWith('No: after DELETE (200) search still finds it')
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
        a.answer == 'Cannot tell: 200, but no archetype lists were found. Outline: {paths: list of 1 × {name: string, count: number, rules: object (1 fields)}}.'
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
        f(p, 'edges.relationshipEndpoints').answer.startsWith('DELETE 200: removed. POST 200: added.')
        f(p, 'edges.replaceNonEmpty').answer.startsWith('Yes: R, rewritten with only R → T2, lists R → T2 and no longer R → T1')
        f(p, 'attributes.clear').answer.startsWith('Stored as an empty string')   // the fake stores attributes as given
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

    def "WS0.15: an update that omits an attribute keeps it, and one that lists fewer archetypes replaces them (P20, P22)"() {
        when:
        def p = probe(allowWrites: true)

        then:
        f(p, 'attributes.omission').answer.startsWith('No: once the update was visible, the omitted attribute was still there')   // the fake merges, as the service does (C17)
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
        f(p, 'attributes.omission').answer.startsWith('No: once the update was visible, the omitted attribute was still there')
        f(p, 'archetypes.replace').answer.startsWith('No:')
        f(p, 'archetypes.replace').answer.contains('merges')
    }

    def "WS0.15: --archetypes overrides the manifests, and without either the probe's own archetypes are used"() {
        when:
        def given = probe(allowWrites: true, archetypes: ['X1', 'X2'])
        service.manifests.clear()
        def none = probe(allowWrites: true, runId: 't2')

        then:
        f(given, 'archetypes.replace').answer.contains('only `X2` remains')
        f(given, 'archetypes.replace').detail.contains('from --archetypes')
        f(none, 'archetypes.replace').answer.contains('only `Ws0ProbeB` remains')
        f(none, 'archetypes.replace').detail.contains('from probe archetypes')
    }

    def "WS0.15e: with a settle window, every read-back waits for the service to catch up, so lagging writes still get answers"() {
        given:
        server.close()
        def lagging = new LaggingService(3)
        seedForeign(lagging)
        server = new FakeGraphHttpServer(lagging).start()

        when:
        def p = probe(allowWrites: true, settleSeconds: 2, pollMillis: 5)

        then:
        f(p, 'baseType.create').answer.startsWith('Yes')
        f(p, 'search.createdVisible').answer.startsWith('Yes: both created entities were found by a search on their ID within')
        f(p, 'attributes.omission').answer.startsWith('No: once the update was visible, the omitted attribute was still there')   // the fake merges, as the service does (C17)
        f(p, 'archetypes.replace').answer.startsWith('Yes')
        ['edges.postToMissing', 'edges.inboundOnCreate', 'edges.getShowsInbound', 'edges.sourceOmission', 'edges.inboundOnPut',
         'edges.targetOmission', 'edges.putToMissing', 'edges.relationshipEndpoints', 'edges.deleteCascades'].every { f(p, it).confidence == 'observed' }
        f(p, 'edges.sourceOmission').answer.startsWith('Yes')
        f(p, 'search.reflectsDelete').answer.startsWith('Yes')
        f(p, 'ids.caseSensitivity').answer.startsWith('Yes')
        f(p, 'paging.updateDuringEnumeration').answer.startsWith('Neither')
        p.cleanupProblems == []
    }

    def "WS0.15e: the same lagging service read once, with no settle window, gives the 'cannot tell' answers of the real runs"() {
        given:
        server.close()
        def lagging = new LaggingService(3)
        seedForeign(lagging)
        server = new FakeGraphHttpServer(lagging).start()

        when:
        def p = probe(allowWrites: true, settleSeconds: 0)

        then:
        f(p, 'baseType.create').answer.startsWith('Cannot tell')
        f(p, 'attributes.omission').answer.startsWith('Cannot tell')
        f(p, 'edges.postToMissing').answer.startsWith('Cannot tell')
    }

    /** C15: an entity's read model shows relationships as they were at its own last write; another entity's write does not refresh it. */
    static class StaleOtherEndService extends FakeGraphService {
        final Map<String, List> snapshot = [:]

        private void snap(String id) {
            snapshot[id] = edges.findAll { it[0] == id || it[1] == id }.collect { new ArrayList(it) }
        }

        @Override HttpResponse create(Map<String, Object> d) {
            def r = super.create(d)
            if (r.status == 200) snap(d.id as String)
            r
        }

        @Override HttpResponse replace(String id, Map<String, Object> d) {
            def r = super.replace(id, d)
            if (r.status == 200) snap(id)
            r
        }

        @Override Map<String, Object> renderEntity(String id) {
            def e = super.renderEntity(id)
            if (snapshot.containsKey(id)) e.relationships = snapshot[id].collect { [source: it[0], target: it[1], type: it[2], sourceType: 'Resource'] }
            e
        }
    }

    /** C13: a write that arrives while the previous write to the same entity is still being applied is lost. */
    static class LosingService extends LaggingService {
        LosingService(int lag) { super(lag) }

        @Override HttpResponse replace(String id, Map<String, Object> d) {
            if (((stale[id]?.left ?: 0) as int) > 0) { requests << [op: 'PUT', id: id, body: d, lost: true]; return new HttpResponse(status: 200) }
            super.replace(id, d)
        }
    }

    /** Relationship removals show in the written entity's read model only {@code lag} reads after the write. */
    static class DelayedRemovalService extends FakeGraphService {
        final int lag
        final Map<String, Map> pending = [:]

        DelayedRemovalService(int lag) { this.lag = lag }

        @Override HttpResponse replace(String id, Map<String, Object> d) {
            List before = edges.findAll { it[0] == id || it[1] == id }.collect { new ArrayList(it) }
            def r = super.replace(id, d)
            if (r.status == 200) pending[id] = [left: lag, rels: before]
            r
        }

        @Override Map<String, Object> renderEntity(String id) {
            def e = super.renderEntity(id)
            Map p = pending[id]
            if (p && (p.left as int) > 0) {
                p.left = (p.left as int) - 1
                List now = e.relationships as List
                List old = (p.rels as List).collect { [source: it[0], target: it[1], type: it[2], sourceType: 'Resource'] }
                e.relationships = (now + old).unique()   // additions show at once; removals lag
            }
            e
        }
    }

    /** Attributes merged on update (as the fake does, C17), and an attribute written as an empty string removed. */
    static class MergingAttributesService extends FakeGraphService {
        @Override HttpResponse replace(String id, Map<String, Object> d) {
            def r = super.replace(id, d)
            if (r.status == 200) (entities[id].attributes as Map).removeAll { k, v -> v == '' }
            r
        }
    }

    /** Stores no relationships at all: every relationship check's starting state is missing. */
    static class NoRelationshipsService extends FakeGraphService {
        @Override HttpResponse create(Map<String, Object> d) { super.create(d + [relationships: []]) }
        @Override HttpResponse replace(String id, Map<String, Object> d) { super.replace(id, d + [relationships: []]) }
        @Override HttpResponse addRelationship(String id, Map<String, Object> rel) { requests << [op: 'ADD_RELATIONSHIP', id: id, body: rel]; new HttpResponse(status: 200) }
    }

    /**
     * C18 as run 20261121-215954 saw it: a relationship a write replaces is only hidden. It comes back once a
     * relationship of the same entity is deleted through the single-relationship endpoint.
     */
    static class ResurfacingService extends FakeGraphService {
        final Set<List<String>> hidden = new LinkedHashSet<>()

        @Override HttpResponse replace(String id, Map<String, Object> d) {
            Set<List<String>> before = edges.findAll { it[0] == id || it[1] == id }.collect { new ArrayList(it) } as Set
            def r = super.replace(id, d)
            if (r.status == 200) {
                Set<List<String>> gone = before - edges
                hidden.addAll(gone)
                edges.addAll(gone)
            }
            r
        }

        @Override HttpResponse deleteRelationship(String sourceId, String targetId, String type) {
            def r = super.deleteRelationship(sourceId, targetId, type)
            hidden.removeAll { it[0] == sourceId }
            r
        }

        @Override Map<String, Object> renderEntity(String id) {
            def e = super.renderEntity(id)
            e.relationships = (e.relationships as List).findAll { Map rel -> !hidden.contains([rel.source, rel.target, rel.type]) }
            e
        }
    }

    def "WS0.15h: with the default fake, each relationship check confirms its starting state and answers"() {
        when:
        def p = probe(allowWrites: true)

        then:
        f(p, 'edges.replaceNonEmpty').answer.contains('and still so on a later read')
        f(p, 'edges.reassertSame').answer == 'Yes: R lists the same relationships as before.'
        f(p, 'edges.deleteCascades').answer == 'Yes: R listed R → T2 before T2 was deleted, and no longer does.'
        f(p, 'edges.relationshipEndpoints').detail.contains('{sourceID}/relationships/{targetID}/{type}')
    }

    def "WS0.15h: when the starting state never shows, every relationship check says so instead of a void answer"() {
        given:
        server.close()
        def none = new NoRelationshipsService()
        seedForeign(none)
        server = new FakeGraphHttpServer(none).start()

        when:
        def p = probe(allowWrites: true)

        then:
        ['edges.sourceOmission', 'edges.targetOmission', 'edges.replaceNonEmpty', 'edges.reassertSame', 'edges.relationshipEndpoints', 'edges.deleteCascades'].every {
            f(p, it).confidence == 'inconclusive' && f(p, it).answer.contains('starting state never showed')
        }
    }

    def "WS0.15h: a replaced relationship that resurfaces after an endpoint DELETE is reported (C18)"() {
        given:
        server.close()
        def resurfacing = new ResurfacingService()
        seedForeign(resurfacing)
        server = new FakeGraphHttpServer(resurfacing).start()

        when:
        def p = probe(allowWrites: true)

        then:
        f(p, 'edges.replaceNonEmpty').answer.startsWith('Yes')
        f(p, 'edges.relationshipEndpoints').answer.contains('R → T1, replaced earlier, reappeared (C18)')
    }

    def "WS0.15g: a relationship removal that shows a few reads late is waited for, not reported as 'not removed'"() {
        given:
        server.close()
        def delayed = new DelayedRemovalService(3)
        seedForeign(delayed)
        server = new FakeGraphHttpServer(delayed).start()

        when:
        def settled = probe(allowWrites: true, settleSeconds: 2, pollMillis: 5)

        then:
        f(settled, 'edges.sourceOmission').answer.startsWith('Yes')
        f(settled, 'edges.targetOmission').answer.startsWith('Yes')
        f(settled, 'edges.replaceNonEmpty').answer.startsWith('Yes')
    }

    def "WS0.15g: the same delayed removal, read once, gives the 'No' of run 214027"() {
        given:
        server.close()
        def delayed = new DelayedRemovalService(3)
        seedForeign(delayed)
        server = new FakeGraphHttpServer(delayed).start()

        when:
        def once = probe(allowWrites: true, settleSeconds: 0)

        then:
        f(once, 'edges.sourceOmission').answer.startsWith('No')
    }

    def "WS0.15g: with merged attributes, omission keeps an attribute and an empty string clears it"() {
        given:
        server.close()
        def merging = new MergingAttributesService()
        seedForeign(merging)
        server = new FakeGraphHttpServer(merging).start()

        when:
        def p = probe(allowWrites: true)

        then:
        f(p, 'attributes.omission').answer.startsWith('No: once the update was visible, the omitted attribute was still there')
        f(p, 'attributes.clear').answer.startsWith('Yes: the attribute is gone')
    }

    def "WS0.15f: every write in the relationship and attribute checks carries an increasing revision marker"() {
        when:
        probe(allowWrites: true)

        then:
        def revs = service.requests.findAll { it.op in ['POST', 'PUT'] && (it.body?.id as String)?.endsWith('edge-A') }*.body*.attributes*.rev
        revs == (1..revs.size())*.toString()
        revs.size() >= 5
    }

    def "WS0.15f/h: with a stale other end (C15, since withdrawn), outcomes are read on the written entity, and a missing starting state is reported"() {
        given:
        server.close()
        def stale = new StaleOtherEndService()
        seedForeign(stale)
        server = new FakeGraphHttpServer(stale).start()

        when:
        def p = probe(allowWrites: true)

        then:
        f(p, 'edges.sourceOmission').answer.startsWith('Cannot tell: the starting state never showed: A did not list A → B')
        f(p, 'edges.inboundOnCreate').answer.startsWith('Yes')
        f(p, 'edges.otherEndRefreshed').answer.startsWith('No')
    }

    def "WS0.15f: settled writes still get answers from a service that loses a write sent too soon (C13)"() {
        given:
        server.close()
        def losing = new LosingService(3)
        seedForeign(losing)
        server = new FakeGraphHttpServer(losing).start()

        when:
        def p = probe(allowWrites: true, settleSeconds: 2, pollMillis: 5)

        then:
        f(p, 'attributes.omission').answer.startsWith('No: once the update was visible, the omitted attribute was still there')   // the fake merges, as the service does (C17)
        f(p, 'edges.sourceOmission').answer.startsWith('Yes')
        f(p, 'archetypes.replace').answer.startsWith('Yes')
        !losing.requests.any { it.lost && !((it.id as String).contains('-stab-') || (it.id as String).contains('never-created')) }
    }

    def "WS0.15f: --remove-leftovers deletes earlier runs' probe entities, skips IDs with a slash, and touches nothing else"() {
        given:
        service.seed([id: 'ws0-probe-20250101-000000-seed-1', baseType: 2, attributes: [probe: 'ws0']])
        service.seed([id: 'ws0-probe-20250101-000000-seed-2', baseType: 2, attributes: [probe: 'ws0']])
        service.seed([id: 'ws0-probe-20250101-000000-a/b', baseType: 2, attributes: [probe: 'ws0']])
        def foreignBefore = service.entities.keySet().findAll { !it.startsWith('ws0-probe-') }

        when:
        def p = probe(allowWrites: true, removeLeftovers: true)

        then:
        f(p, 'probe.leftoversRemoved').answer.startsWith('Removed 2 of 3.')
        f(p, 'probe.leftoversRemoved').answer.contains('Not addressable by path')
        !service.entities.containsKey('ws0-probe-20250101-000000-seed-1')
        service.entities.containsKey('ws0-probe-20250101-000000-a/b')
        service.entities.keySet().findAll { !it.startsWith('ws0-probe-') } == foreignBefore
    }

    def "WS0.15f: without --remove-leftovers, leftovers are only reported"() {
        given:
        service.seed([id: 'ws0-probe-20250101-000000-seed-1', baseType: 2, attributes: [probe: 'ws0']])

        when:
        def p = probe(allowWrites: true)

        then:
        !p.findings*.key.contains('probe.leftoversRemoved')
        service.entities.containsKey('ws0-probe-20250101-000000-seed-1')
    }

    def "WS0.15e: poll stops as soon as the condition holds, and makes exactly one attempt with no window"() {
        given:
        def p = new ContractProbe(options(settleSeconds: 2, pollMillis: 1))
        int calls = 0

        when:
        def r = p.poll({ ++calls }, { it >= 3 })

        then:
        r.result == 3
        r.done
        r.attempts == 3

        when:
        calls = 0
        def once = new ContractProbe(options(settleSeconds: 0)).poll({ ++calls }, { false })

        then:
        once.attempts == 1
        !once.done
    }

    def "WS0.15: archetype names are found inside the manifests export bundle, and only names are recorded (C11)"() {
        given:
        server.close()
        def bundled = new FakeGraphService() {
            @Override HttpResponse manifests() {
                new HttpResponse(status: 200, headers: ['Content-Type': 'application/json'], body: [paths: [[
                    entities: ['PII-Cor': [name: 'PII-Cor']], entityRelationships: [], policies: [p1: [rules: 'PII-rule']],
                    manifests: [banking: [name: 'banking', subjectArchetypes: [[name: 'Customer', subjects: [[name: 'PII-Cor']], reconciliationPath: 'PII-path']],
                                          resourceArchetypes: [[name: 'Account'], [name: 'Card']], constraints: [[name: 'c1', rules: 'PII-rule']]]]]]])
            }
        }
        seedForeign(bundled)
        server = new FakeGraphHttpServer(bundled).start()

        when:
        def p = probe()
        ProbeReport.write(p, dir)
        def recorded = new File(dir, 'exchanges.jsonl').readLines().collect { new groovy.json.JsonSlurper().parseText(it) }.find { it.path == '/admin/manifests' }.body

        then:
        f(p, 'manifests.archetypes').answer == '1 manifest(s), nested in an export bundle rather than the documented list (C11). Subject archetypes: Customer. Resource archetypes: Account, Card.'
        p.contract.resourceArchetypes == ['Account', 'Card']
        recorded.paths[0].manifests.banking.subjectArchetypes == [[name: 'Customer']]
        recorded.paths[0].entities == Scrubber.REDACTED
        recorded.paths[0].policies == Scrubber.REDACTED
        !new File(dir, 'exchanges.jsonl').text.contains('PII-')
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
