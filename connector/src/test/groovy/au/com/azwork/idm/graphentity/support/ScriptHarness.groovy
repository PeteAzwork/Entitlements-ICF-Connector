package au.com.azwork.idm.graphentity.support

import groovy.json.JsonOutput
import groovyx.net.http.RESTClient
import org.identityconnectors.common.logging.Log
import org.identityconnectors.common.security.GuardedString

/**
 * Evaluates the real ICF operation scripts the way the Scripted REST harness does: a GroovyShell with the
 * harness bindings ({@code configuration}, {@code connection}, {@code log}, {@code operation},
 * {@code objectClass}, {@code uid}, {@code attributes}, {@code filter}, {@code handler}, {@code options}).
 *
 * {@code configuration} is a duck-typed stand-in for ScriptedRESTConfiguration carrying the standard
 * properties and a {@code propertyBag} populated as IDM would from {@code customConfiguration}.
 */
class ScriptHarness {

    static final File SCRIPT_DIR = new File('src/main/scripts')

    static Object fakeConfiguration(String serviceAddress, Map<String, Object> propertyBag = [:]) {
        def bag = [objectTypes: Fixtures.objectTypesJson(), timestampSearchParam: 'updatedAfter',
                   pageSizeDefault: 200, deleteMissingTolerated: true] + propertyBag
        new Expando(serviceAddress: new URI(serviceAddress), username: 'svc-idm-sync',
            password: new GuardedString('secret'.toCharArray()), propertyBag: bag,
            customConfiguration: "objectTypes='${JsonOutput.toJson(Fixtures.objectTypesMap()).replace("'", "\\'")}'")
    }

    static RESTClient restClient(String baseUrl) {
        def client = new RESTClient(baseUrl)
        client.headers['Authorization'] = 'Basic ' + 'svc-idm-sync:secret'.bytes.encodeBase64()
        client.handler.failure = client.handler.success  // never throw on non-2xx; RestClientEntityApi maps statuses
        client
    }

    static Object run(String scriptName, Map<String, Object> bindings) {
        def binding = new Binding(bindings)
        def shell = new GroovyShell(ScriptHarness.classLoader, binding)
        shell.evaluate(new File(SCRIPT_DIR, scriptName))
    }

    static Map<String, Object> baseBindings(String baseUrl, Log log, Map<String, Object> propertyBag = [:]) {
        [configuration: fakeConfiguration(baseUrl, propertyBag), connection: restClient(baseUrl), log: log] as Map<String, Object>
    }
}
