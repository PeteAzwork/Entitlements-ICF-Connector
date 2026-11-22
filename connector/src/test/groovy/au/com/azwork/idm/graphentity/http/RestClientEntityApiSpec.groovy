package au.com.azwork.idm.entitlementsaccelerator.http

import au.com.azwork.idm.entitlementsaccelerator.support.Fixtures
import au.com.azwork.idm.entitlementsaccelerator.support.ScriptHarness
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

import static com.github.tomakehurst.wiremock.client.WireMock.*

/**
 * Design §2 — the HTTP contract, exercised through the real HTTP Builder RESTClient that the harness
 * binds as {@code connection}, against a WireMock stand-in for the service.
 */
class RestClientEntityApiSpec extends Specification {

    @Shared WireMockServer wm = new WireMockServer(WireMockConfiguration.options().dynamicPort())
    RestClientEntityApi api

    def setupSpec() { wm.start() }
    def cleanupSpec() { wm.stop() }

    def setup() {
        wm.resetAll()
        api = new RestClientEntityApi(ScriptHarness.restClient("http://localhost:${wm.port()}"))
    }

    def "GET /admin/entities/{id} returns the parsed document; the raw API key is sent and no Authorization header"() {
        given:
        wm.stubFor(get(urlEqualTo('/admin/entities/Sanne')).willReturn(okJson(groovy.json.JsonOutput.toJson(Fixtures.sanneEntity()))))

        when:
        def r = api.get('Sanne')

        then:
        r.status == 200
        r.body.id == 'Sanne'
        r.body.relationships == [[source: 'Sanne', target: 'Current-Account-1-Sanne', type: 'Owns', sourceType: 'Subject']]
        wm.verify(getRequestedFor(urlEqualTo('/admin/entities/Sanne'))
            .withHeader('X-API-KEY', equalTo(Fixtures.API_KEY))
            .withoutHeader('Authorization')
            .withHeader('Accept', containing('application/json')))
    }

    def "GET of a missing entity returns 404 without throwing"() {
        given:
        wm.stubFor(get(urlPathMatching('/admin/entities/.*')).willReturn(aResponse().withStatus(404)))

        expect:
        api.get('nobody').status == 404
    }

    def "POST /admin/entities (no trailing slash) sends the write model as JSON; the service answers 200 with no body"() {
        given:
        wm.stubFor(post(urlEqualTo('/admin/entities')).willReturn(aResponse().withStatus(200)))

        when:
        def r = api.create(Fixtures.sanneWire())

        then:
        r.status == 200
        r.body == null
        wm.verify(postRequestedFor(urlEqualTo('/admin/entities'))
            .withHeader('Content-Type', containing('application/json'))
            .withRequestBody(equalToJson(groovy.json.JsonOutput.toJson(Fixtures.sanneWire()))))
    }

    def "PUT /admin/entities/{id} sends the full document"() {
        given:
        wm.stubFor(put(urlEqualTo('/admin/entities/Sanne')).willReturn(ok()))

        when:
        def r = api.replace('Sanne', Fixtures.sanneWire())

        then:
        r.status == 200
        wm.verify(putRequestedFor(urlEqualTo('/admin/entities/Sanne'))
            .withRequestBody(matchingJsonPath('$.relationships[0].sourceID', equalTo('Sanne')))
            .withRequestBody(matchingJsonPath('$.relationships[0].targetID', equalTo('Current-Account-1-Sanne'))))
    }

    def "DELETE /admin/entities/{id} returns 200 with the deleted entity"() {
        given:
        wm.stubFor(delete(urlEqualTo('/admin/entities/Sanne')).willReturn(okJson(groovy.json.JsonOutput.toJson(Fixtures.sanneEntity()))))

        when:
        def r = api.delete('Sanne')

        then:
        r.status == 200
        r.body.id == 'Sanne'
    }

    def "GET /admin/manifests returns the manifest list (P22)"() {
        given:
        wm.stubFor(get(urlEqualTo('/admin/manifests')).willReturn(okJson('[{"name":"m","subjectArchetypes":[{"name":"Customer"}],"resourceArchetypes":[{"name":"Account"}]}]')))

        when:
        def r = api.manifests()

        then:
        r.status == 200
        r.body*.name == ['m']
        wm.verify(getRequestedFor(urlEqualTo('/admin/manifests')).withHeader('X-API-KEY', equalTo(Fixtures.API_KEY)))
    }

    def "search POSTs the filter and paging fields as a JSON body to /admin/entities/search (P14)"() {
        given:
        wm.stubFor(post(urlEqualTo('/admin/entities/search')).willReturn(okJson('{"results":[],"pagedResultsCookie":null,"totalResults":0,"hasMore":false}')))

        when:
        def r = api.search([objectClass: 4, filter: [operation: 'EQUALS', left: 'name', right: 'x'], pageSize: 200, pagedResultsCookie: '200'])

        then:
        r.status == 200
        r.body.results == []
        wm.verify(postRequestedFor(urlEqualTo('/admin/entities/search'))
            .withHeader('Content-Type', containing('application/json'))
            .withHeader('X-API-KEY', equalTo(Fixtures.API_KEY))
            .withRequestBody(equalToJson('{"objectClass":4,"filter":{"operation":"EQUALS","left":"name","right":"x"},"pageSize":200,"pagedResultsCookie":"200"}')))
    }

    def "an unfiltered search sends {} as the body, not an empty request"() {
        given:
        wm.stubFor(post(urlEqualTo('/admin/entities/search')).willReturn(okJson('{"results":[]}')))

        when:
        api.search([:])

        then:
        wm.verify(postRequestedFor(urlEqualTo('/admin/entities/search')).withRequestBody(equalToJson('{}')))
    }

    def "search never uses GET: the service answers GET on the collection with 405"() {
        given:
        wm.stubFor(post(urlEqualTo('/admin/entities/search')).willReturn(okJson('{"results":[]}')))

        when:
        api.search([objectClass: 4])

        then:
        wm.verify(0, getRequestedFor(urlPathMatching('/admin/entities.*')))
    }

    @Unroll
    def "non-2xx status #status is returned, not thrown, with the raw body available for logging"() {
        given:
        wm.stubFor(get(urlEqualTo('/admin/entities/x')).willReturn(aResponse().withStatus(status)
            .withHeader('Content-Type', 'application/json').withBody('{"error":"boom"}')))

        when:
        def r = api.get('x')

        then:
        r.status == status
        r.rawBody.contains('boom')

        where:
        status << [400, 401, 403, 409, 429, 500, 503]
    }

    def "response headers are captured (Retry-After on 429)"() {
        given:
        wm.stubFor(put(urlEqualTo('/admin/entities/x')).willReturn(aResponse().withStatus(429).withHeader('Retry-After', '12')))

        expect:
        api.replace('x', [ID: 'x']).header('Retry-After') == '12'
    }

    def "a non-JSON error body does not break the response (body null, rawBody kept)"() {
        given:
        wm.stubFor(get(urlEqualTo('/admin/entities/x')).willReturn(aResponse().withStatus(502)
            .withHeader('Content-Type', 'text/html').withBody('<html>Bad Gateway</html>')))

        when:
        def r = api.get('x')

        then:
        r.status == 502
        r.body == null
        r.rawBody.contains('Bad Gateway')
    }

    def "a connection failure surfaces as an IOException for the operations to map to ConnectorIOException"() {
        given:
        def deadPort = wm.port() + 1  // nothing listening (best effort)
        def dead = new RestClientEntityApi(ScriptHarness.restClient("http://localhost:${deadPort}"))

        when:
        dead.get('x')

        then:
        thrown(IOException)
    }

    @Unroll
    def "IDs are URL-encoded as a single path segment: '#id' → '#encoded'"() {
        expect:
        RestClientEntityApi.encodeSegment(id) == encoded

        where:
        id             | encoded
        'Sanne'        | 'Sanne'
        'a b'          | 'a%20b'
        'a/b'          | 'a%2Fb'
        'a?b=c'        | 'a%3Fb%3Dc'
        'a#b'          | 'a%23b'
        'Sanne-Groen'  | 'Sanne-Groen'
        'ünï'          | '%C3%BCn%C3%AF'
    }

    def "GET addresses an encoded ID"() {
        given:
        wm.stubFor(get(urlEqualTo('/admin/entities/a%2Fb')).willReturn(okJson('{"ID":"a/b"}')))

        expect:
        api.get('a/b').status == 200
    }
}
