package au.com.azwork.idm.entitlementsaccelerator.scenario

import au.com.azwork.idm.entitlementsaccelerator.config.ConnectorConfig
import au.com.azwork.idm.entitlementsaccelerator.http.HttpErrorMapper
import au.com.azwork.idm.entitlementsaccelerator.http.OpContext
import au.com.azwork.idm.entitlementsaccelerator.http.RestClientEntityApi
import au.com.azwork.idm.entitlementsaccelerator.ops.CreateOperation
import au.com.azwork.idm.entitlementsaccelerator.ops.OperationContext
import au.com.azwork.idm.entitlementsaccelerator.ops.SearchOperation
import au.com.azwork.idm.entitlementsaccelerator.ops.TestOperation
import au.com.azwork.idm.entitlementsaccelerator.support.CapturingLog
import au.com.azwork.idm.entitlementsaccelerator.support.Fixtures
import au.com.azwork.idm.entitlementsaccelerator.support.ScriptHarness
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.identityconnectors.common.logging.Log
import org.identityconnectors.framework.common.exceptions.InvalidAttributeValueException
import org.identityconnectors.framework.common.objects.ConnectorObject
import org.identityconnectors.framework.common.objects.ObjectClass
import org.identityconnectors.framework.common.objects.OperationOptionsBuilder
import org.identityconnectors.framework.common.objects.Uid
import org.identityconnectors.framework.common.objects.filter.FilterBuilder
import spock.lang.Shared
import spock.lang.Specification

import static com.github.tomakehurst.wiremock.client.WireMock.*

/**
 * WS4.09 — the connector against the real service's recorded exchanges (WS0.11, run 20261121-215954), through the
 * real HTTP client, operations and decoders. Where {@code RecordedShapesSpec} holds the fake to the recorded shapes,
 * this spec holds the connector to them: if the service drifts, re-recording makes this spec fail first.
 */
class RecordedContractSpec extends Specification {

    static final File RECORDED = new File('src/test/resources/wiremock/recorded')
    static final String EDGE_A = 'ws0-probe-20261121-215954-edge-A'
    static final String EDGE_B = 'ws0-probe-20261121-215954-edge-B'
    static final ObjectClass PROBE = new ObjectClass('probe')
    static final ObjectClass CUSTOMER = new ObjectClass('customer')

    @Shared WireMockServer wm = new WireMockServer(WireMockConfiguration.options().dynamicPort())
    CapturingLog log = new CapturingLog()

    def setupSpec() { wm.start() }
    def cleanupSpec() { wm.stop() }
    def setup() { wm.resetAll() }

    /** Registers a recording exactly as recorded: request pattern and response. */
    void stub(String file) {
        wm.addStubMapping(StubMapping.buildFrom(new File(RECORDED, file).text))
    }

    /** Registers a recording's response for any request with the same method and path (the connector adds fields the probe did not send). */
    void stubResponseOnly(String file) {
        Map recording = new JsonSlurper().parse(new File(RECORDED, file)) as Map
        Map request = recording.request as Map
        recording.request = [method: request.method, urlPath: request.urlPath ?: request.url]
        wm.addStubMapping(StubMapping.buildFrom(JsonOutput.toJson(recording)))
    }

    /** A configuration shaped like the probe's entities: a resource type with the probe's attributes, and a subject type. */
    OperationContext ctx(Map<String, Object> objectTypes = defaultTypes()) {
        def config = ConnectorConfig.from([serviceAddress: "http://localhost:${wm.port()}".toString(), apiKey: Fixtures.API_KEY,
                                           objectTypes: objectTypes])
        new OperationContext(config, new RestClientEntityApi(ScriptHarness.restClient("http://localhost:${wm.port()}")), log.asLog())
    }

    static Map<String, Object> defaultTypes() {
        [probe   : [idmResource: 'managed/probe', entityKind: 'resource', idProperty: 'id', attributes: ['probe', 'rev']],
         customer: [idmResource: 'managed/user', entityKind: 'subject', idProperty: 'userName', attributes: ['Name'], archetypes: ['Customer']]]
    }

    def "the create body the connector builds is the one the service accepted, attributes.ID and the P17 edge included"() {
        given:
        stub('create-entity.json')   // matches only the exact recorded body
        stub('get-entity.json')      // the verification read

        when:
        def uid = new CreateOperation(ctx()).execute(PROBE, Fixtures.toAttributes([
            __NAME__           : EDGE_B, probe: 'ws0', rev: '1', directArchetypes: [],
            entityRelationships: ["{\"sourceID\":\"${EDGE_A}\",\"targetID\":\"${EDGE_B}\",\"type\":\"Ws0ProbeEdge\"}".toString()]]), null)

        then:
        uid.uidValue == EDGE_B
        log.messagesAt(Log.Level.WARN).empty   // the recorded read model shows the relationship
    }

    def "a read by ID decodes the recorded read model: attribute objects and the relationship in the P17 shape"() {
        given:
        stub('get-entity.json')
        List<ConnectorObject> seen = []

        when:
        new SearchOperation(ctx()).execute(PROBE, FilterBuilder.equalTo(new Uid(EDGE_B)), { seen << it; true }, null)

        then:
        seen*.uid*.uidValue == [EDGE_B]
        seen[0].getAttributeByName('rev').value == ['1']
        seen[0].getAttributeByName('probe').value == ['ws0']
        seen[0].getAttributeByName('entityRelationships').value ==
            ["{\"sourceID\":\"${EDGE_A}\",\"targetID\":\"${EDGE_B}\",\"type\":\"Ws0ProbeEdge\"}".toString()]
        seen[0].getAttributeByName('lastEvaluated').value == ['2026-11-21T11:00:08Z']
    }

    def "a recorded search page is scoped by base type and isArchetypes (P19, C9) and paged with the service's cookie"() {
        given:
        stubResponseOnly('search-page.json')
        Map body = (new JsonSlurper().parse(new File(RECORDED, 'search-page.json')) as Map).response.jsonBody as Map
        List<String> expected = body.results.findAll { it.baseType == 'Subject' && 'Customer' in it.isArchetypes }*.uid
        List<ConnectorObject> seen = []

        when:
        def result = new SearchOperation(ctx()).execute(CUSTOMER, null, { seen << it; true }, new OperationOptionsBuilder().setPageSize(5).build())

        then:
        seen*.uid*.uidValue == expected
        !expected.empty
        seen.every { it.getAttributeByName('directArchetypes').value.contains('Customer') }
        seen.every { it.getAttributeByName('entityRelationships') == null }   // search items carry none (D4)
        (body.pagedResultsCookie == null) == (result.pagedResultsCookie == null)
        wm.verify(postRequestedFor(urlEqualTo('/admin/entities/search'))
            .withRequestBody(matchingJsonPath('$.objectClass', equalTo('4')))
            .withRequestBody(matchingJsonPath('$.pageSize', equalTo('5'))))
    }

    def "the recorded 400 maps to InvalidAttributeValueException carrying the service's validation message"() {
        given:
        stub('create-invalid-400.json')
        def pattern = (new JsonSlurper().parse(new File(RECORDED, 'create-invalid-400.json')) as Map).request.bodyPatterns[0].equalToJson
        Map sent = (pattern instanceof String ? new JsonSlurper().parseText(pattern) : pattern) as Map   // stored as a JSON string
        def context = ctx()

        when:
        def response = context.api.create(sent)
        HttpErrorMapper.raise(response, OpContext.CREATE, context.config)

        then:
        def e = thrown(InvalidAttributeValueException)
        e.message.contains('could not be converted to System.String')
        response.header('Content-Type').contains('json')
    }

    def "the Test operation reads archetype names from the recorded export bundle (C11)"() {
        given:
        stubResponseOnly('search-page.json')
        stub('manifests-bundle.json')
        Map bundle = (new JsonSlurper().parse(new File(RECORDED, 'manifests-bundle.json')) as Map).response.jsonBody as Map
        Map firstManifest = (bundle.paths[0].manifests as Map).values().first() as Map
        String subject = firstManifest.subjectArchetypes[0].name
        String resource = firstManifest.resourceArchetypes[0].name

        when:
        new TestOperation(ctx([
            person : [idmResource: 'managed/user', entityKind: 'subject', idProperty: 'userName', archetypes: [subject]],
            product: [idmResource: 'managed/product', entityKind: 'resource', idProperty: 'id', archetypes: [resource]],
            other  : [idmResource: 'managed/other', entityKind: 'resource', idProperty: 'id', archetypes: ['No Such Archetype']]])).execute()

        then:
        log.messagesAt(Log.Level.WARN).size() == 1
        log.messagesAt(Log.Level.WARN)[0].contains('No Such Archetype')
    }
}
