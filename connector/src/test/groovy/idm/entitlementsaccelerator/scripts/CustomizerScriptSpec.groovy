package idm.entitlementsaccelerator.scripts

import idm.entitlementsaccelerator.support.Fixtures
import idm.entitlementsaccelerator.support.ScriptHarness
import org.apache.http.HttpHost
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.http.protocol.BasicHttpContext
import org.identityconnectors.common.security.GuardedString
import org.identityconnectors.framework.common.exceptions.ConfigurationException
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import spock.lang.Shared
import spock.lang.Specification

import static com.github.tomakehurst.wiremock.client.WireMock.*

/**
 * Design §4, §14 — the customizer is the single point that knows about authentication. The Scripted REST
 * harness calls the `customize { init { HttpClientBuilder -> } }` DSL; this spec drives the same DSL with a
 * minimal stand-in and inspects the resulting client.
 */
class CustomizerScriptSpec extends Specification {

    @Shared WireMockServer wm = new WireMockServer(WireMockConfiguration.options().dynamicPort())

    def setupSpec() { wm.start() }
    def cleanupSpec() { wm.stop() }
    def setup() { wm.resetAll() }

    /** Delegate for the `customize { }` block: records each lifecycle closure the script declares. */
    static class CustomizeDelegate {
        final Map<String, Closure> captured
        CustomizeDelegate(Map<String, Closure> captured) { this.captured = captured }
        void init(Closure c) { captured.init = c }
        void release(Closure c) { captured.release = c }
        void decorate(Closure c) { captured.decorate = c }
    }

    /** Minimal emulation of the harness DSL: collects the init/release/decorate closures. */
    static Map<String, Closure> evaluate(Object configuration) {
        Map<String, Closure> captured = [:]
        def binding = new Binding([configuration: configuration, customize: { Closure c ->
            c.delegate = new CustomizeDelegate(captured)
            c.resolveStrategy = Closure.DELEGATE_FIRST
            c()
        }])
        new GroovyShell(CustomizerScriptSpec.classLoader, binding).evaluate(new File(ScriptHarness.SCRIPT_DIR, 'CustomizerScript.groovy'))
        captured
    }

    def "init sends the API key from the encrypted configuration, raw, in X-API-KEY, and no Authorization header"() {
        given:
        wm.stubFor(get(urlEqualTo('/admin/entities/x')).willReturn(ok()))
        def captured = evaluate(ScriptHarness.fakeConfiguration("http://localhost:${wm.port()}"))
        def builder = HttpClientBuilder.create()

        when:
        captured.init(builder)
        builder.build().execute(new HttpHost('localhost', wm.port()), new HttpGet('/admin/entities/x'), new BasicHttpContext()).close()

        then:
        wm.verify(getRequestedFor(urlEqualTo('/admin/entities/x'))
            .withHeader('X-API-KEY', equalTo(Fixtures.API_KEY))
            .withoutHeader('Authorization'))
    }

    def "the key header name can be overridden with apiKeyHeader in customConfiguration"() {
        given:
        wm.stubFor(get(urlEqualTo('/admin/entities/x')).willReturn(ok()))
        def captured = evaluate(ScriptHarness.fakeConfiguration("http://localhost:${wm.port()}", [apiKeyHeader: 'X-Custom-Key']))
        def builder = HttpClientBuilder.create()

        when:
        captured.init(builder)
        builder.build().execute(new HttpHost('localhost', wm.port()), new HttpGet('/admin/entities/x'), new BasicHttpContext()).close()

        then:
        wm.verify(getRequestedFor(urlEqualTo('/admin/entities/x'))
            .withHeader('X-Custom-Key', equalTo(Fixtures.API_KEY))
            .withoutHeader('X-API-KEY'))
    }

    def "init sets connect and socket timeouts (no unbounded hangs during recon)"() {
        given:
        def captured = evaluate(ScriptHarness.fakeConfiguration('http://localhost:5276'))
        def builder = HttpClientBuilder.create()

        when:
        captured.init(builder)

        then:
        def cfg = builder.@defaultRequestConfig
        cfg != null
        cfg.connectTimeout > 0
        cfg.socketTimeout > 0
    }

    def "release closure exists and is safe to call"() {
        given:
        def captured = evaluate(ScriptHarness.fakeConfiguration('http://localhost:5276'))

        expect:
        captured.release != null
        captured.release(HttpClientBuilder.create()) == null
    }

    def "decorate wraps the configured client in a RESTClient for the service that returns non-2xx rather than throwing (WS3.01)"() {
        given:
        wm.stubFor(get(urlEqualTo('/admin/entities/x')).willReturn(aResponse().withStatus(404)))
        def captured = evaluate(ScriptHarness.fakeConfiguration("http://localhost:${wm.port()}"))
        def builder = HttpClientBuilder.create()
        captured.init(builder)

        when:
        def client = captured.decorate(builder.build())
        def status = new idm.entitlementsaccelerator.http.RestClientEntityApi(client).get('x').status

        then:
        client instanceof groovyx.net.http.RESTClient
        status == 404
        wm.verify(getRequestedFor(urlEqualTo('/admin/entities/x')).withHeader('X-API-KEY', equalTo(Fixtures.API_KEY)))
    }

    def "init takes the configuration from its delegate, as the harness provides it"() {
        given:
        wm.stubFor(get(urlEqualTo('/admin/entities/x')).willReturn(ok()))
        def captured = evaluate(null)
        def builder = HttpClientBuilder.create()
        def init = captured.init.rehydrate(ScriptHarness.fakeConfiguration("http://localhost:${wm.port()}"), captured.init.owner, captured.init.thisObject)

        when:
        init(builder)
        builder.build().execute(new HttpHost('localhost', wm.port()), new HttpGet('/admin/entities/x'), new BasicHttpContext()).close()

        then:
        wm.verify(getRequestedFor(urlEqualTo('/admin/entities/x')).withHeader('X-API-KEY', equalTo(Fixtures.API_KEY)))
    }

    def "timeouts can be set through connectTimeoutMs and socketTimeoutMs in customConfiguration"() {
        given:
        def captured = evaluate(ScriptHarness.fakeConfiguration('http://localhost:5276', [connectTimeoutMs: '1500', socketTimeoutMs: '2500']))
        def builder = HttpClientBuilder.create()

        when:
        captured.init(builder)

        then:
        builder.@defaultRequestConfig.connectTimeout == 1500
        builder.@defaultRequestConfig.socketTimeout == 2500
    }

    def "the script source does not embed credentials and references the configuration for them"() {
        given:
        def src = new File(ScriptHarness.SCRIPT_DIR, 'CustomizerScript.groovy').text

        expect:
        !src.contains(Fixtures.API_KEY)
        !src.contains('Authorization')
        src.contains('configuration')
    }

    def "proxyAddress sends every request through the proxy, with the API key (deployment guide §5.2)"() {
        given: 'the service host does not exist; only the proxy can answer for it'
        wm.stubFor(get(urlEqualTo('/admin/entities/x')).willReturn(ok()))
        def config = ScriptHarness.fakeConfiguration('https://service.invalid')
        config.proxyAddress = new URI("http://localhost:${wm.port()}")
        def captured = evaluate(config)
        def builder = HttpClientBuilder.create()

        when:
        captured.init(builder)
        int status = builder.build().execute(new HttpHost('service.invalid', 80), new HttpGet('/admin/entities/x'), new BasicHttpContext())
            .withCloseable { it.statusLine.statusCode }

        then:
        status == 200
        wm.verify(getRequestedFor(urlEqualTo('/admin/entities/x')).withHeader('X-API-KEY', equalTo(Fixtures.API_KEY)))
    }

    def "proxy credentials answer the proxy's 407 only, from the encrypted proxyPassword"() {
        given:
        String expected = 'Basic ' + 'proxy-user:proxy-secret'.bytes.encodeBase64().toString()
        wm.stubFor(get(urlEqualTo('/admin/entities/x')).atPriority(2)
            .willReturn(aResponse().withStatus(407).withHeader('Proxy-Authenticate', 'Basic realm="proxy"')))
        wm.stubFor(get(urlEqualTo('/admin/entities/x')).atPriority(1).withHeader('Proxy-Authorization', equalTo(expected))
            .willReturn(ok()))
        def config = ScriptHarness.fakeConfiguration('http://service.invalid')
        config.proxyAddress = new URI("http://localhost:${wm.port()}")
        config.proxyUsername = 'proxy-user'
        config.proxyPassword = new GuardedString('proxy-secret'.toCharArray())
        def captured = evaluate(config)
        def builder = HttpClientBuilder.create()

        when:
        captured.init(builder)
        int status = builder.build().execute(new HttpHost('service.invalid', 80), new HttpGet('/admin/entities/x'), new BasicHttpContext())
            .withCloseable { it.statusLine.statusCode }

        then: 'answered after the challenge; never as Authorization, which would reach the service'
        status == 200
        wm.verify(getRequestedFor(urlEqualTo('/admin/entities/x')).withHeader('Proxy-Authorization', equalTo(expected)))
        wm.verify(0, getRequestedFor(anyUrl()).withHeader('Authorization', matching('.*')))
    }

    def "a proxyAddress that carries credentials, or is not an http URL, is refused without repeating it"() {
        given:
        def config = ScriptHarness.fakeConfiguration('http://localhost:5276')
        config.proxyAddress = address
        def captured = evaluate(config)

        when:
        captured.init(HttpClientBuilder.create())

        then:
        def e = thrown(ConfigurationException)
        e.message.contains(expected)
        !e.message.contains('s3cret')

        where:
        address                                    | expected
        'http://user:s3cret@proxy.example.com:3128' | 'must not contain credentials'
        'socks://proxy.example.com:1080'           | 'must be an http or https URL'
    }

    def "no proxyAddress, no proxy"() {
        given:
        def captured = evaluate(ScriptHarness.fakeConfiguration('http://localhost:5276'))
        def builder = HttpClientBuilder.create()

        when:
        captured.init(builder)

        then:
        builder.@proxy == null
    }
}
