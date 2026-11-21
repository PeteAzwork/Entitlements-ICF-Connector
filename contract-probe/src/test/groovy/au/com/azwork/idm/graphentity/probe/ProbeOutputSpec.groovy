package au.com.azwork.idm.graphentity.probe

import au.com.azwork.idm.graphentity.support.FakeGraphHttpServer
import au.com.azwork.idm.graphentity.support.FakeGraphService
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import groovy.json.JsonSlurper
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** What a probe run leaves on disk: useful to people, safe to share, replayable as WireMock stubs (WS0.11). */
class ProbeOutputSpec extends Specification {

    @Shared @TempDir File dir
    @Shared ContractProbe probe
    @Shared List<File> files

    def setupSpec() {
        def service = new FakeGraphService()
        service.seed([id: 'PII-Sanne', baseType: 4, attributes: [name: 'PII-Sanne Groen', mail: 'PII-mail@example.com'], directArchetypes: ['Customer']])
        service.seed([id: 'PII-Account-1', baseType: 2, attributes: [name: 'PII-Current'], directArchetypes: ['Account']])
        service.seedEdge('PII-Sanne', 'PII-Account-1', 'Owns')
        service.seedManifest('m', ['Customer'], ['Account', 'Card'])
        def server = new FakeGraphHttpServer(service).start()
        try {
            probe = new ContractProbe(new ProbeOptions(baseUrl: server.baseUrl, apiKey: FakeGraphHttpServer.DEFAULT_API_KEY.toCharArray(),
                runId: 'out1', outDir: dir, allowWrites: true, timeoutSeconds: 5)).run()
            ProbeReport.write(probe, dir)
        } finally {
            server.close()
        }
        files = []
        dir.eachFileRecurse { if (it.isFile()) files << it }
    }

    def "writes the report, findings, exchange log and WireMock mappings"() {
        expect:
        new File(dir, 'report.md').isFile()
        new File(dir, 'findings.json').isFile()
        new File(dir, 'exchanges.jsonl').readLines().size() == probe.http.exchanges.size()
        new File(dir, 'wiremock/mappings').listFiles().size() == probe.http.exchanges.count { it.status > 0 && it.auth == 'valid' }
    }

    def "no output file contains the API key, valid or deliberately invalid"() {
        expect:
        files.findAll { it.text.contains(FakeGraphHttpServer.DEFAULT_API_KEY) || it.text.contains(ProbeHttp.INVALID_KEY) }*.name == []
    }

    def "no output file contains any ID or value of an entity the probe did not create"() {
        expect:
        files.findAll { it.text.contains('PII-') }*.name == []
    }

    def "foreign entities keep their shape in recordings so they stay useful"() {
        given:
        def exchanges = new File(dir, 'exchanges.jsonl').readLines().collect { new JsonSlurper().parseText(it) }
        def item = exchanges.collectMany { (it.body instanceof Map && it.body.results) ? it.body.results : [] }
            .find { it.uid?.startsWith('scrubbed-') && it.directArchetypes == ['Customer'] }
        def entity = exchanges.find { it.method == 'GET' && it.status == 200 && it.body instanceof Map && it.body.id?.startsWith('scrubbed-') }.body

        expect:
        item.baseType == 'Subject'
        item.attributes.keySet() == ['name', 'mail'] as Set
        item.attributes.name == Scrubber.REDACTED
        entity.relationships.size() == 1
        entity.relationships[0].type == 'Owns'
        [entity.relationships[0].source, entity.relationships[0].target].every { it.startsWith('scrubbed-') }
    }

    def "the report has a section per WS0 task with evidence references, and records cleanup"() {
        given:
        def md = new File(dir, 'report.md').text

        expect:
        ['WS0.02', 'WS0.03', 'WS0.04', 'WS0.05', 'WS0.06', 'WS0.07', 'WS0.08', 'WS0.09', 'WS0.10', 'WS0.15'].every { md.contains("### ${it}") }
        md =~ /\| #\d+/
        md.contains('| Base-type numbering (objectClass) | P18 (4 = Subject, 2 = Resource) |')
        md.contains('| Base type of a created entity | confirmed: 4 = Subject |')
        md.contains('| ID filter field | `ID` |')
        md.contains('| Manifest archetypes | Subject: Customer; Resource: Account, Card |')
        md.contains('| Search request | `POST /admin/entities/search` with a JSON body |')
        md.contains('probe entity IDs were deleted and confirmed gone')
        !md.contains('Timestamp')
    }

    def "a non-JSON body is only written for error responses, truncated"() {
        expect:
        ProbeReport.persistedRawBody(new Exchange(status: 200, rawBody: 'Cor Jette')) == null
        ProbeReport.persistedRawBody(new Exchange(status: 502, rawBody: 'Bad Gateway')) == 'Bad Gateway'
        ProbeReport.persistedRawBody(new Exchange(status: 500, rawBody: 'x' * 600)).length() == 501
    }

    def "evidence numbers are compressed into ranges"() {
        expect:
        ProbeReport.evidence([1, 2, 3, 7]) == '#1–#3 #7'
        ProbeReport.evidence([4, 5]) == '#4 #5'
        ProbeReport.evidence([9]) == '#9'
        ProbeReport.evidence([]) == ''
    }

    def "every finding that ran cites at least one exchange (an absence of rate-limit headers has nothing to cite)"() {
        expect:
        probe.findings.findAll { it.confidence != 'not-tested' && it.evidence.empty }*.key == ['limits.headers']
    }

    def "findings.json is machine-readable and carries the discovered contract"() {
        given:
        def json = new JsonSlurper().parse(new File(dir, 'findings.json'))

        expect:
        json.mode == 'read-write'
        json.contract.idFilterField == 'ID'
        json.contract.pageSizeParam == 'pageSize'
        json.contract.cursorParam == 'pagedResultsCookie'
        !json.contract.toString().contains('PII-')
        json.findings.every { it.confidence in Finding.CONFIDENCE }
        json.cleanupProblems == []
    }

    def "the WireMock mappings load and replay a recorded search"() {
        given:
        def wm = new WireMockServer(WireMockConfiguration.options().dynamicPort().usingFilesUnderDirectory(new File(dir, 'wiremock').path))
        wm.start()

        when:
        def resp = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:${wm.port()}/admin/entities/search"))
                .header('Content-Type', 'application/json')
                .POST(HttpRequest.BodyPublishers.ofString('{"pageSize":1}')).build(),
            HttpResponse.BodyHandlers.ofString())

        then:
        wm.listAllStubMappings().mappings.size() == new File(dir, 'wiremock/mappings').listFiles().size()
        resp.statusCode() == 200
        new JsonSlurper().parseText(resp.body()).results.size() == 1

        cleanup:
        wm.stop()
    }
}
