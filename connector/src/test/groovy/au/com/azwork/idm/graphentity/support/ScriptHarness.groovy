package au.com.azwork.idm.graphentity.support

import groovy.json.JsonOutput
import groovyx.net.http.RESTClient
import org.apache.http.impl.client.HttpClientBuilder
import org.identityconnectors.common.logging.Log
import org.identityconnectors.common.security.GuardedString

/**
 * Evaluates the real ICF operation scripts the way the Scripted REST harness does: a GroovyShell with the
 * harness bindings ({@code configuration}, {@code connection}, {@code customizedConnection}, {@code log},
 * {@code operation}, {@code objectClass}, {@code uid}, {@code id}, {@code attributes}, {@code filter},
 * {@code handler}, {@code options}, {@code builder}).
 *
 * <p>As in the harness (WS3.01, Ping's Scripted REST reference), {@code connection} is the Apache HttpClient
 * built by the real CustomizerScript's {@code init}, with the configuration as the closure's delegate, and
 * {@code customizedConnection} is what its {@code decorate} returns for that client.
 *
 * {@code configuration} is a duck-typed stand-in for ScriptedRESTConfiguration carrying the standard
 * properties and a {@code propertyBag} populated as IDM would from {@code customConfiguration}.
 */
class ScriptHarness {

    static final File SCRIPT_DIR = new File('src/main/scripts')

    static Object fakeConfiguration(String serviceAddress, Map<String, Object> propertyBag = [:]) {
        def bag = [objectTypes: Fixtures.objectTypesJson(), pageSizeDefault: 200, deleteMissingTolerated: true] + propertyBag
        // The Scripted REST `password` property carries the API key (decision P13); there is no username.
        new Expando(serviceAddress: new URI(serviceAddress),
            password: new GuardedString(Fixtures.API_KEY.toCharArray()), propertyBag: bag,
            customConfiguration: "objectTypes='${JsonOutput.toJson(Fixtures.objectTypesMap()).replace("'", "\\'")}'")
    }

    /** Runs the real CustomizerScript as the harness does: [connection: HttpClient, customizedConnection: decorated]. */
    static Map<String, Object> customizedClients(Object configuration) {
        Map<String, Closure> captured = [:]
        def binding = new Binding([customize: { Closure c ->
            c.delegate = new Expando(init: { Closure i -> captured.init = i }, decorate: { Closure d -> captured.decorate = d },
                release: { Closure r -> captured.release = r })
            c.resolveStrategy = Closure.DELEGATE_FIRST
            c()
        }])
        new GroovyShell(ScriptHarness.classLoader, binding).evaluate(new File(SCRIPT_DIR, 'CustomizerScript.groovy'))
        def builder = HttpClientBuilder.create()
        Closure init = captured.init.rehydrate(configuration, captured.init.owner, captured.init.thisObject)
        init(builder)
        def httpClient = builder.build()
        Closure decorate = captured.decorate.rehydrate(configuration, captured.decorate.owner, captured.decorate.thisObject)
        [connection: httpClient, customizedConnection: decorate(httpClient)] as Map<String, Object>
    }

    static RESTClient restClient(String baseUrl) {
        def client = new RESTClient(baseUrl)
        client.headers['X-API-KEY'] = Fixtures.API_KEY
        client.handler.failure = client.handler.success  // never throw on non-2xx; RestClientEntityApi maps statuses
        client
    }

    static Object run(String scriptName, Map<String, Object> bindings) {
        def binding = new Binding(bindings)
        def shell = new GroovyShell(ScriptHarness.classLoader, binding)
        shell.evaluate(new File(SCRIPT_DIR, scriptName))
    }

    static Map<String, Object> baseBindings(String baseUrl, Log log, Map<String, Object> propertyBag = [:]) {
        def configuration = fakeConfiguration(baseUrl, propertyBag)
        [configuration: configuration, log: log] + customizedClients(configuration) as Map<String, Object>
    }
}
