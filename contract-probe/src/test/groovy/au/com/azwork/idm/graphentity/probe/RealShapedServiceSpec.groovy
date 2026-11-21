package au.com.azwork.idm.graphentity.probe

import au.com.azwork.idm.graphentity.http.HttpResponse
import au.com.azwork.idm.graphentity.support.FakeGraphHttpServer
import au.com.azwork.idm.graphentity.support.FakeGraphService
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir

/**
 * Regression for the first real run (2026-11-21): the service's documents use {@code uid}, a string
 * {@code baseType} and {@code isArchetypes}, and every GET by ID returned 500. The probe leaked foreign data
 * (its scrubber only knew {@code ID}) and reported answers built on failed reads as "observed".
 * This fake reproduces those shapes and failures.
 */
class RealShapedServiceSpec extends Specification {

    /** Search results shaped like the real EntitySearchResultItem; every GET by ID fails with 500. */
    static class RealShapedFakeService extends FakeGraphService {
        @Override
        HttpResponse get(String id) {
            requests << [op: 'GET', id: id]
            new HttpResponse(status: 500)
        }

        /** Like the real service: a PUT to a missing entity answers 200 (whether it creates anything is unknown). */
        @Override
        HttpResponse replace(String id, Map<String, Object> document) {
            entities.containsKey(id) ? super.replace(id, document) : new HttpResponse(status: 200)
        }

        @Override
        Map<String, Object> render(String id) {
            def d = super.render(id)
            [uid: d.ID, name: d.ID, baseType: d.BaseType == 4 ? 'Subject' : 'Resource',
             attributes: (d.attributes as Map).findAll { k, v -> k != 'ID' },
             isArchetypes: d.directArchetypes, directArchetypes: [], lastEvaluated: d.lastUpdated]
        }
    }

    @Shared @TempDir File dir
    @Shared ContractProbe probe
    @Shared List<File> files = []
    @Shared Map<String, Map> before

    def setupSpec() {
        def service = new RealShapedFakeService()
        service.seed([ID: 'PII-Cor', BaseType: 4, attributes: [ID: 'PII-Cor', Name: 'PII-Cor Jette'], directArchetypes: ['Customer']])
        service.seed([ID: 'PII-CC-12345', BaseType: 2, attributes: [ID: 'PII-CC-12345', Recipient: 'PII-Piet'], directArchetypes: ['CreditCardApplication']])
        service.seed([ID: 'PII-Card-1', BaseType: 2, attributes: [ID: 'PII-Card-1', Type: 'Credit Card'], directArchetypes: ['Account']])
        before = service.entities.collectEntries { k, v -> [(k): new LinkedHashMap(v)] }
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

    def "no output file contains any ID, name or attribute value of an entity the probe did not create"() {
        expect:
        files
        files.findAll { it.text.contains('PII-') }*.name == []
    }

    def "foreign entities in recordings keep their shape: field names, base type and archetypes"() {
        given:
        def item = new File(dir, 'exchanges.jsonl').readLines().collect { new groovy.json.JsonSlurper().parseText(it) }
            .collectMany { it.body instanceof Map && it.body.items ? it.body.items : [] }
            .find { it.isArchetypes == ['Customer'] }

        expect:
        item.uid.startsWith('scrubbed-')
        item.name == item.uid
        item.baseType == 'Subject'
        item.attributes == [Name: Scrubber.REDACTED]
        item.lastEvaluated ==~ /\d{4}-.*/
    }

    def "the document check copes with uid instead of ID and does not crash"() {
        expect:
        !probe.findings*.key.any { it.endsWith('.unexpected') }
        f(probe, 'document.searchVsGet').confidence == 'inconclusive'
        f(probe, 'document.searchVsGet').answer.contains('500')
    }

    def "base types are read from baseType and archetypes from isArchetypes"() {
        expect:
        f(probe, 'types.baseTypes').answer == 'Resource: 2, Subject: 1'
        f(probe, 'types.archetypes').detail.contains('Customer')
    }

    def "answers that depend on a failed read are inconclusive, never observed"() {
        expect:
        ['edges.postToMissing', 'create.duplicate', 'edges.inboundOnCreate', 'edges.getShowsInbound',
         'edges.putOmissionDeletesInbound', 'edges.inboundOnPut', 'edges.putToMissing', 'edges.deleteCascades'].every {
            f(probe, it).confidence == 'inconclusive' && f(probe, it).answer.startsWith('Cannot tell')
        }
    }

    def "a 2xx PUT to a missing entity is not reported as an upsert when the read-back fails"() {
        expect:
        f(probe, 'errors.putMissing').answer.contains('cannot be told')
        !f(probe, 'errors.putMissing').answer.contains('creates missing entities')
    }

    def "cleanup does not claim entities are gone when it cannot read them back"() {
        expect:
        probe.cleanupProblems
        probe.cleanupProblems.every { it.startsWith('could not confirm') }
        !new File(dir, 'report.md').text.contains('confirmed gone')
    }

    def "foreign entities are never written to"() {
        expect:
        probe.http.exchanges.findAll { it.method in ['PUT', 'DELETE'] }.every { it.path.contains('ws0-probe-real-') }
    }
}
