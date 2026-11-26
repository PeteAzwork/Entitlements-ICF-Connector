package idm.entitlementsaccelerator.support

import idm.entitlementsaccelerator.model.EntityDocument
import groovy.json.JsonSlurper
import spock.lang.Shared
import spock.lang.Specification

/**
 * WS0.11 — the service's real document shapes, recorded by contract-probe run 20261121-215954 and kept under
 * {@code src/test/resources/wiremock/recorded/}. The structural cases hold the in-memory fake to those shapes, so
 * the fake cannot drift from the real service unnoticed. The decoding cases run the connector's model on the real
 * documents; they fail on WS2 stubs until EntityDocument is implemented.
 */
class RecordedShapesSpec extends Specification {

    @Shared Map create = load('create-entity.json')
    @Shared Map get = load('get-entity.json')
    @Shared Map search = load('search-page.json')
    @Shared Map invalid = load('create-invalid-400.json')
    @Shared Map manifests = load('manifests-bundle.json')

    static Map load(String name) {
        def url = RecordedShapesSpec.getResource("/wiremock/recorded/${name}")
        assert url, "missing recording ${name}"
        new JsonSlurper().parse(url) as Map
    }

    static Map createBody(Map mapping) { new JsonSlurper().parseText(mapping.request.bodyPatterns[0].equalToJson as String) as Map }

    FakeAcceleratorService service = new FakeAcceleratorService()

    def setup() {
        service.seed(Fixtures.sanneWire()); service.seed(Fixtures.accountWire())
        service.seedEdge(Fixtures.SANNE, Fixtures.SANNE_ACCOUNT, 'Owns')
    }

    // ---------------------------------------------------------------- the fake matches the recorded shapes

    def "the fake's read model has the recorded top-level fields, attribute objects and relationship fields"() {
        given:
        Map real = get.response.jsonBody as Map
        Map fake = service.renderEntity(Fixtures.SANNE)

        expect:
        fake.keySet() == real.keySet()
        (fake.Attributes as Map).values().every { (it as Map).keySet() == ((real.Attributes as Map).values().first() as Map).keySet() }
        (fake.relationships as List).every { (it as Map).keySet() == ((real.relationships as List).first() as Map).keySet() }
    }

    def "the fake's search response has the recorded envelope and item fields"() {
        given:
        Map real = search.response.jsonBody as Map
        Map fake = service.search([pageSize: 1]).body as Map

        expect:
        fake.keySet() == real.keySet()
        ((fake.results as List).first() as Map).keySet() == ((real.results as List).first() as Map).keySet()
    }

    def "like the service, the fake reports archetypes in isArchetypes and leaves directArchetypes empty (C9)"() {
        expect:
        (search.response.jsonBody.results as List).every { (it.directArchetypes as List).empty }
        (service.search([pageSize: 10]).body.results as List).every { (it.directArchetypes as List).empty && it.isArchetypes }
    }

    def "the fake's 400 has the recorded problem-details fields, including errors"() {
        given:
        Map real = invalid.response.jsonBody as Map
        def fake = service.create([id: 'x', baseType: 2, attributes: [ID: 'x', n: 5]])

        expect:
        fake.status == 400
        (fake.body as Map).keySet() == real.keySet()
        real.errors instanceof Map
    }

    def "the connector's write model has the recorded create body's fields, attributes.ID included (C8)"() {
        given:
        Map real = createBody(create)

        expect:
        Fixtures.sanneWire().keySet() == real.keySet()
        (real.attributes as Map).ID == real.id
        (real.relationships as List).every { (it as Map).keySet() == ['sourceID', 'targetID', 'type'] as Set }
        create.response.status == 200
        !create.response.containsKey('jsonBody')
    }

    def "the recorded manifests are an export bundle with archetype names under paths[].manifests (C11)"() {
        given:
        def bundle = manifests.response.jsonBody

        expect:
        bundle instanceof Map
        def m = (bundle.paths as List).first().manifests as Map
        m.values().every { it instanceof Map && it.name instanceof String }
        m.values().any { (it.subjectArchetypes ?: it.resourceArchetypes) instanceof List }
    }

    def "no recording holds data of an entity the probe did not create"() {
        given:
        def text = ['create-entity.json', 'get-entity.json', 'search-page.json', 'create-invalid-400.json', 'manifests-bundle.json']
            .collect { RecordedShapesSpec.getResource("/wiremock/recorded/${it}").text }.join('\n')
        def ids = (text =~ /"(?:id|uid|ID|source|target|sourceID|targetID)"\s*:\s*"([^"]*)"/).collect { it[1] }

        expect:
        ids
        ids.every { it.startsWith('ws0-probe-') || it.startsWith('scrubbed-') }
    }

    // ---------------------------------------------------------------- the connector's model on the real documents (WS2)

    def "EntityDocument reads the recorded read model, relationship included"() {
        when:
        def doc = EntityDocument.fromEntity(get.response.jsonBody as Map)

        then:
        doc.id == (get.response.jsonBody as Map).id
        doc.relationships.size() == 1
        !doc.attributes.containsKey('ID')
    }

    def "EntityDocument reads every recorded search item"() {
        when:
        def docs = (search.response.jsonBody.results as List).collect { EntityDocument.fromSearchItem(it as Map) }

        then:
        docs*.id.every { it }
        docs*.relationships.every { it == null }
    }

    def "EntityDocument round-trips the recorded create body"() {
        given:
        Map real = createBody(create)

        expect:
        EntityDocument.fromWire(real).toWire() == real
    }
}
