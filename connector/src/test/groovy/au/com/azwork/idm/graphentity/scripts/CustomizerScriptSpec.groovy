package au.com.azwork.idm.graphentity.scripts

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

    /** Minimal emulation of the harness DSL: collects the init/release closures. */
    static Map<String, Closure> evaluate(Object configuration) {
        def captured = [:]
        def binding = new Binding([configuration: configuration, customize: { Closure c ->
            c.delegate = [init: { Closure i -> captured.init = i }, release: { Closure r -> captured.release = r }]
            c.resolveStrategy = Closure.DELEGATE_FIRST
            c()
        }])
        new GroovyShell(CustomizerScriptSpec.classLoader, binding).evaluate(new File(ScriptHarness.SCRIPT_DIR, 'CustomizerScript.groovy'))
        captured
    }

    def "init configures Basic auth from the encrypted configuration and the client sends it"() {
        given:
        wm.stubFor(get(urlEqualTo('/admin/entities/x')).willReturn(ok()))
        def captured = evaluate(ScriptHarness.fakeConfiguration("http://localhost:${wm.port()}"))
        def builder = HttpClientBuilder.create()

        when:
        captured.init(builder)
        def client = builder.build()
        def ctx = new BasicHttpContext()
        client.execute(new HttpHost('localhost', wm.port()), new HttpGet('/admin/entities/x'), ctx).close()

        then:
        wm.verify(getRequestedFor(urlEqualTo('/admin/entities/x'))
            .withHeader('Authorization', equalTo('Basic ' + 'svc-idm-sync:secret'.bytes.encodeBase64().toString())))
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
        !src.contains('svc-idm-sync')
        !src.contains("'secret'")
        src.contains('configuration')
    }
}
