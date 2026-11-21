package au.com.azwork.idm.graphentity.probe

import au.com.azwork.idm.graphentity.http.HttpResponse
import au.com.azwork.idm.graphentity.support.FakeGraphHttpServer
import au.com.azwork.idm.graphentity.support.FakeGraphService
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
 */
class RealShapedServiceSpec extends Specification {

    static class RealShapedFakeService extends FakeGraphService {
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
            entities.containsKey(id) ? super.delete(id) : new HttpResponse(status: 200)
        }

        @Override
        HttpResponse create(Map<String, Object> document) {
            document.id == null ? new HttpResponse(status: 500) : super.create(document)
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
        service.manifests << [name: 'PII-manifest-name-is-kept', subjectArchetypes: [[name: 'Customer', subjects: [[name: 'PII-Cor']], reconciliationPath: 'PII-path']],
                              resourceArchetypes: [[name: 'Account', resources: [[name: 'PII-Card-1']]], [name: 'CreditCardApplication']],
                              constraints: [[name: 'c', rules: 'PII-rule']]]
        def server = new FakeGraphHttpServer(service).start()
        try {
            probe = new ContractProbe(new ProbeOptions(baseUrl: server.baseUrl, apiKey: FakeGraphHttpServer.DEFAULT_API_KEY.toCharArray(),
                runId: 'real', outDir: dir, allowWrites: true, timeoutSeconds: 5)).run()
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
        f(probe, 'types.archetypes').detail.contains('3 of 3 entities have no direct archetype')
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
        f(probe, 'create.duplicate').detail.contains('Read back by search')
        f(probe, 'attributes.omission').answer.startsWith('Yes')
        f(probe, 'archetypes.replace').answer.startsWith('Yes')
        f(probe, 'ids.space').answer.contains('a search finds it')
    }

    def "C2, C3 and C4 are recorded as the real service answers them"() {
        expect:
        f(probe, 'errors.putMissing').answer.startsWith('200')
        f(probe, 'errors.putMissing').answer.contains('by search does not find it')
        !f(probe, 'errors.putMissing').answer.contains('creates missing entities')
        f(probe, 'errors.deleteMissing').answer.startsWith('200')
        f(probe, 'errors.postWithoutId').answer.startsWith('500')
    }

    def "cleanup confirms removal by search when GET fails, and the report says how"() {
        expect:
        probe.cleanupProblems == []
        probe.cleanupConfirmedBy.values().every { it == 'search' }
        new File(dir, 'report.md').text.contains('by a search on the ID finding nothing, because GET failed (C1)')
    }

    def "foreign entities are never written to"() {
        expect:
        probe.http.exchanges.findAll { it.method in ['PUT', 'DELETE'] }.every { it.path.contains('ws0-probe-real-') }
    }
}
