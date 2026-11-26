package idm.entitlementsaccelerator.probe

import idm.entitlementsaccelerator.http.HttpResponse
import idm.entitlementsaccelerator.support.FakeAcceleratorHttpServer
import idm.entitlementsaccelerator.support.FakeAcceleratorService
import groovy.json.JsonSlurper
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Regression for the first real run (2026-11-21), and the realigned probe's answer to it (WS0.15). The real
 * service answered every GET by ID with 500 (C1), a PUT or DELETE of a missing entity with 200 (C2, C3), and a
 * create without an ID with 500 (C4); existing entities carried archetypes in isArchetypes only. The first probe
 * leaked foreign data and reported answers built on failed reads as "observed". The realigned probe reads back by
 * search when GET fails, so it can still answer everything except relationship questions.
 *
 * <p>The second real run (20261121-173153) added two behaviours, modelled here with one possible explanation each:
 * every documented-EntityDTO create got 500 while the design-shaped creates of the first run were accepted (here:
 * the service needs {@code attributes.ID}, C8), and entities the first run deleted were still found by search
 * (here: DELETE answers 200 and does nothing, C10).
 */
class RealShapedServiceSpec extends Specification {

    static class RealShapedFakeService extends FakeAcceleratorService {
        @Override
        HttpResponse get(String id) {
            requests << [op: 'GET', id: id]
            new HttpResponse(status: 500)
        }

        @Override
        HttpResponse replace(String id, Map<String, Object> document) {
            entities.containsKey(id) ? super.replace(id, document) : new HttpResponse(status: 200)
        }

        @Override
        HttpResponse delete(String id) {
            requests << [op: 'DELETE', id: id]
            new HttpResponse(status: 200)
        }

        @Override
        HttpResponse create(Map<String, Object> document) {
            document.id == null || (document.attributes as Map)?.ID != document.id ? new HttpResponse(status: 500) : super.create(document)
        }
    }

    @Shared @TempDir File dir
    @Shared ContractProbe probe
    @Shared List<File> files = []

    def setupSpec() {
        def service = new RealShapedFakeService()
        service.seed([id: 'PII-Cor', baseType: 4, attributes: [Name: 'PII-Cor Jette'], isArchetypes: ['Customer']])
        service.seed([id: 'PII-CC-12345', baseType: 2, attributes: [Recipient: 'PII-Piet'], isArchetypes: ['CreditCardApplication']])
        service.seed([id: 'PII-Card-1', baseType: 2, attributes: [Type: 'Credit Card'], isArchetypes: ['Account']])
        service.seedEdge('PII-Cor', 'PII-Card-1', 'OWNS')
        service.seed([id: 'ws0-probe-20261121-155100-seed-1', baseType: 2, attributes: [probe: 'ws0'], isArchetypes: ['Ws0Probe']])
        service.manifests << [name: 'PII-manifest-name-is-kept', subjectArchetypes: [[name: 'Customer', subjects: [[name: 'PII-Cor']], reconciliationPath: 'PII-path']],
                              resourceArchetypes: [[name: 'Account', resources: [[name: 'PII-Card-1']]], [name: 'CreditCardApplication']],
                              constraints: [[name: 'c', rules: 'PII-rule']]]
        def server = new FakeAcceleratorHttpServer(service).start()
        try {
            probe = new ContractProbe(new ProbeOptions(baseUrl: server.baseUrl, apiKey: FakeAcceleratorHttpServer.DEFAULT_API_KEY.toCharArray(),
                runId: 'real', outDir: dir, allowWrites: true, timeoutSeconds: 5, settleSeconds: 0)).run()
            ProbeReport.write(probe, dir)
        } finally {
            server.close()
        }
        dir.eachFileRecurse { if (it.isFile()) files << it }
    }

    static Finding f(ContractProbe p, String key) {
        def found = p.findings.find { it.key == key }
        assert found, "no finding ${key}; have ${p.findings*.key}"
        found
    }

    def "no output file contains any ID, name, attribute value or manifest data of anything the probe did not create"() {
        expect:
        files
        files.findAll { it.text.replace('PII-manifest-name-is-kept', '').contains('PII-') }*.name == []
    }

    def "a by-ID filter on a foreign entity is recorded with the ID aliased"() {
        given:
        def filters = new File(dir, 'exchanges.jsonl').readLines().collect { new JsonSlurper().parseText(it) }
            .findAll { it.requestBody instanceof Map && it.requestBody.filter?.operation == 'EQUALS' && it.requestBody.filter?.left == 'ID' }
            .collect { it.requestBody.filter.right }

        expect:
        filters.any { it.startsWith('scrubbed-') }
        filters.every { it.startsWith('scrubbed-') || it.startsWith('ws0-probe-real-') }
    }

    def "manifests are recorded as archetype names only"() {
        given:
        def body = new File(dir, 'exchanges.jsonl').readLines().collect { new JsonSlurper().parseText(it) }
            .find { it.path == '/admin/manifests' }.body

        expect:
        body == [[name: 'PII-manifest-name-is-kept', subjectArchetypes: [[name: 'Customer']],
                  resourceArchetypes: [[name: 'Account'], [name: 'CreditCardApplication']], constraints: Scrubber.REDACTED]]
    }

    def "foreign entities in recordings keep their shape: field names, base type and archetypes"() {
        given:
        def item = new File(dir, 'exchanges.jsonl').readLines().collect { new JsonSlurper().parseText(it) }
            .collectMany { it.body instanceof Map && it.body.results ? it.body.results : [] }
            .find { it.isArchetypes == ['Customer'] }

        expect:
        item.uid.startsWith('scrubbed-')
        item.name == item.uid
        item.baseType == 'Subject'
        item.attributes == [Name: Scrubber.REDACTED]
        item.lastEvaluated ==~ /\d{4}-.*/
    }

    def "GET of an existing entity is tried and its failure reported (C1); nothing crashes"() {
        expect:
        !probe.findings*.key.any { it.endsWith('.unexpected') }
        f(probe, 'get.existingEntity').answer.startsWith('No: 500')
        probe.contract.getById == 'fails (500)'
        f(probe, 'document.readModelFields').confidence == 'inconclusive'
    }

    def "base types are read as names from search, and archetypes from isArchetypes"() {
        expect:
        f(probe, 'types.baseTypes').answer == 'Resource: 2, Subject: 1'
        f(probe, 'types.archetypes').detail.contains('Customer')
        f(probe, 'types.archetypes').detail.contains('0 of 3 entities have no archetype')
    }

    def "relationship answers need the read model, so they are inconclusive, never observed"() {
        expect:
        ['edges.postToMissing', 'edges.inboundOnCreate', 'edges.getShowsInbound', 'edges.sourceOmission', 'edges.inboundOnPut',
         'edges.targetOmission', 'edges.putToMissing', 'edges.relationshipEndpoints', 'edges.deleteCascades'].every {
            f(probe, it).confidence == 'inconclusive' && f(probe, it).answer.startsWith('Cannot tell')
        }
    }

    def "answers that only need attributes or archetypes are read back by search while GET fails"() {
        expect:
        f(probe, 'baseType.create').answer.startsWith('Yes')
        f(probe, 'create.duplicate').confidence == 'observed'
        f(probe, 'create.duplicate').answer.startsWith('409')
        f(probe, 'attributes.omission').answer.startsWith('No')   // merged, as the real service does (C17)
        f(probe, 'archetypes.replace').answer.startsWith('Yes')
        f(probe, 'ids.space').answer.contains('a search finds it')
    }

    def "C2, C3 and C4 are recorded as the real service answers them"() {
        expect:
        f(probe, 'errors.putMissing').answer.startsWith('200')
        f(probe, 'errors.putMissing').answer.contains('by search')
        f(probe, 'errors.putMissing').answer.contains('does not find it')
        !f(probe, 'errors.putMissing').answer.contains('creates missing entities')
        f(probe, 'errors.deleteMissing').answer.startsWith('200')
        f(probe, 'errors.postWithoutId').answer.startsWith('500')
    }

    def "the designed body, with attributes.ID, is accepted, and the body without it is refused (C8)"() {
        expect:
        probe.contract.createShape == 'EntityDTO with attributes.ID (C8)'
        f(probe, 'create.shape').detail.contains('2. EntityDTO without attributes.ID → 500')
    }

    def "a DELETE that leaves the entity in search is reported, and cleanup lists what it could not remove"() {
        expect:
        f(probe, 'search.reflectsDelete').answer.startsWith('No')
        probe.contract.searchReflectsDelete == false
        probe.cleanupProblems
        probe.cleanupProblems.every { it.contains('still found by search') }
        new File(dir, 'report.md').text.contains('**Some probe entities may remain.**')
        !new File(dir, 'report.md').text.contains('confirmed gone')
    }

    def "the first run's leftover entities are reported, and do not count as real entities"() {
        expect:
        f(probe, 'probe.leftovers').answer.startsWith('Yes: 1, from run(s) 20261121-155100 ×1')
        f(probe, 'types.baseTypes').answer == 'Resource: 2, Subject: 1'
    }

    def "foreign entities are never written to"() {
        expect:
        probe.http.exchanges.findAll { it.method in ['PUT', 'DELETE'] }.every { it.path.contains('ws0-probe-real-') }
    }
}
