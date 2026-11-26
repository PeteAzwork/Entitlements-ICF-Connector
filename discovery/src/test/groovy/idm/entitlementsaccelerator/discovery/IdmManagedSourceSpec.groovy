package idm.entitlementsaccelerator.discovery

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

import static com.github.tomakehurst.wiremock.client.WireMock.*

/** WS6.08 — reading managed.json from IDM over REST: read-only, its own credentials, password from the environment. */
class IdmManagedSourceSpec extends Specification {

    static final String MANAGED = new File('src/test/resources/managed-sample.json').text

    @Shared WireMockServer wm = new WireMockServer(WireMockConfiguration.options().dynamicPort())
    @TempDir Path tmp

    def setupSpec() { wm.start() }
    def cleanupSpec() { wm.stop() }
    def setup() { wm.resetAll() }

    String getUrl() { "http://localhost:${wm.port()}" }

    def "fetches config/managed with IDM's header credentials and makes no other request"() {
        given:
        wm.stubFor(get(urlEqualTo('/openidm/config/managed')).willReturn(okJson(MANAGED)))

        when:
        String body = IdmManagedSource.fetch(url + '/', 'discovery-reader', 's3cret')

        then:
        ManagedSchemaReader.read(body)*.name == ['user', 'account', 'role']
        wm.verify(1, getRequestedFor(urlEqualTo('/openidm/config/managed'))
            .withHeader('X-OpenIDM-Username', equalTo('discovery-reader'))
            .withHeader('X-OpenIDM-Password', equalTo('s3cret')))
        wm.allServeEvents.size() == 1
        wm.allServeEvents.every { it.request.method.value() == 'GET' }
    }

    def "a refused login is an IOException naming the account but never the password"() {
        given:
        wm.stubFor(get(urlEqualTo('/openidm/config/managed')).willReturn(aResponse().withStatus(401)))

        when:
        IdmManagedSource.fetch(url, 'discovery-reader', 's3cret')

        then:
        def e = thrown(IOException)
        e.message.contains('discovery-reader')
        e.message.contains('401')
        !e.message.contains('s3cret')
    }

    def "without the password variable nothing is requested"() {
        when:
        IdmManagedSource.fetch(url, 'discovery-reader', null)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains(IdmManagedSource.PASSWORD_ENV)
        wm.allServeEvents.empty
    }

    def "the command line takes the password from IDM_DISCOVERY_PASSWORD and generates from the live managed.json"() {
        given:
        wm.stubFor(get(urlEqualTo('/openidm/config/managed')).willReturn(okJson(MANAGED)))
        File answers = tmp.resolve('answers.json').toFile()
        answers.text = '{"entityKinds":{"account":"resource"},"archetypes":{"account":["Account"]}}'
        def out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream()

        when:
        int code = DiscoveryTool.run(['--idm-url', url, '--idm-user', 'discovery-reader', '--types', 'account',
                                      '--answers', answers.path, '--out', tmp.resolve('out').toString()],
            new PrintStream(out, true, 'UTF-8'), new PrintStream(err, true, 'UTF-8'), [IDM_DISCOVERY_PASSWORD: 's3cret'])

        then:
        code == DiscoveryTool.OK
        tmp.resolve('out/objectTypes.json').toFile().isFile()
        !out.toString('UTF-8').contains('s3cret')
        !err.toString('UTF-8').contains('s3cret')
    }
}
