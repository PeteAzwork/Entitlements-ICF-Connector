package idm.entitlementsaccelerator.loadtest

import idm.entitlementsaccelerator.support.FakeAcceleratorHttpServer
import idm.entitlementsaccelerator.support.FakeAcceleratorService
import com.sun.net.httpserver.HttpServer
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/** Reading the service back (docs/load-testing.md §4), against the in-memory stand-in over HTTP. */
class ServiceClientSpec extends Specification {

    @Shared FakeAcceleratorService fake = new FakeAcceleratorService()
    @Shared @AutoCleanup FakeAcceleratorHttpServer server = new FakeAcceleratorHttpServer(fake, 'k-123').start()

    def setupSpec() {
        (1..1500).each { fake.seed([id: "lt-r1-u${it}".toString(), baseType: 4, attributes: [givenName: 'Load'], directArchetypes: ['Customer']]) }
        fake.seed([id: 'someone-else', baseType: 4, attributes: [:], directArchetypes: ['Customer']])
        fake.seedEdge('lt-r1-u1', 'lt-r1-a1', 'Owns')
    }

    ServiceClient client() { new ServiceClient(server.baseUrl, 'X-API-KEY', 'k-123'.toCharArray()) }

    def "a prefix search pages through the run's entities only, with a STARTSWITH filter on ID"() {
        given:
        def c = client()

        when:
        def found = c.findByPrefix('lt-r1-')

        then:
        found.size() == 1500
        found.every { ServiceClient.idOf(it).startsWith('lt-r1-') }
        !c.prefixFilterRefused
        c.statusCounts() == ['200': 2]   // two pages of 1,000
    }

    def "one entity by an EQUALS filter on ID; the read model by GET, with its relationships"() {
        given:
        def c = client()

        expect:
        (c.findById('lt-r1-u7').attributes as Map).givenName == 'Load'
        c.findById('nobody') == null
        with(c.read('lt-r1-u1')) {
            status == 200
            (body.relationships as List).any { it.source == 'lt-r1-u1' && it.target == 'lt-r1-a1' && it.type == 'Owns' }
        }
    }

    def "a wrong key fails loudly, naming only the status"() {
        when:
        new ServiceClient(server.baseUrl, 'X-API-KEY', 'wrong'.toCharArray()).reachable()

        then:
        def e = thrown(ServiceClient.ServiceException)
        e.status == 401
        e.message == 'the service answered HTTP 401'
    }

    def "when the service refuses a STARTSWITH filter on ID, the client pages through everything and filters itself"() {
        given: 'a service that answers 400 to any filtered search'
        def items = (1..3).collect { [uid: "lt-r2-u${it}".toString(), attributes: [:]] } + [[uid: 'other', attributes: [:]]]
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.loopbackAddress, 0), 0)
        s.createContext('/admin/entities/search') { ex ->
            String body = ex.requestBody.text
            byte[] out = (body.contains('STARTSWITH') ? '{"title":"unsupported filter"}'
                : groovy.json.JsonOutput.toJson([results: items, pagedResultsCookie: null])).bytes
            ex.sendResponseHeaders(body.contains('STARTSWITH') ? 400 : 200, out.length)
            ex.responseBody.write(out)
            ex.close()
        }
        s.start()
        def c = new ServiceClient("http://127.0.0.1:${s.address.port}", 'X-API-KEY', 'k'.toCharArray())

        when:
        def found = c.findByPrefix('lt-r2-')

        then:
        found*.uid == ['lt-r2-u1', 'lt-r2-u2', 'lt-r2-u3']
        c.prefixFilterRefused

        cleanup:
        s.stop(0)
    }

    def "a 429 on a search is waited out and counted, not failed"() {
        given:
        int calls = 0
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.loopbackAddress, 0), 0)
        s.createContext('/admin/entities/search') { ex ->
            ex.requestBody.text
            if (calls++ == 0) { ex.responseHeaders.add('Retry-After', '1'); ex.sendResponseHeaders(429, -1); ex.close(); return }
            byte[] out = '{"results":[],"pagedResultsCookie":null}'.bytes
            ex.sendResponseHeaders(200, out.length); ex.responseBody.write(out); ex.close()
        }
        s.start()
        def c = new ServiceClient("http://127.0.0.1:${s.address.port}", 'X-API-KEY', 'k'.toCharArray())

        when:
        c.findByPrefix('x')

        then:
        c.statusCounts() == ['200': 1, '429': 1]

        cleanup:
        s.stop(0)
    }
}
