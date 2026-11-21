package au.com.azwork.idm.graphentity.scripts

import au.com.azwork.idm.graphentity.support.Fixtures
import au.com.azwork.idm.graphentity.support.ScriptHarness
import org.apache.http.HttpHost
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.http.protocol.BasicHttpContext
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

    def "the script source does not embed credentials and references the configuration for them"() {
        given:
        def src = new File(ScriptHarness.SCRIPT_DIR, 'CustomizerScript.groovy').text

        expect:
        !src.contains(Fixtures.API_KEY)
        !src.contains('Authorization')
        src.contains('configuration')
    }
}
