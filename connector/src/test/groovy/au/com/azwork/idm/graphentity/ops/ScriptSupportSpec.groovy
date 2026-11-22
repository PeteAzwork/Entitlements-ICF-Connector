package au.com.azwork.idm.entitlementsaccelerator.ops

import au.com.azwork.idm.entitlementsaccelerator.http.RestClientEntityApi
import au.com.azwork.idm.entitlementsaccelerator.support.CapturingLog
import au.com.azwork.idm.entitlementsaccelerator.support.Fixtures
import au.com.azwork.idm.entitlementsaccelerator.support.ScriptHarness
import org.identityconnectors.common.security.GuardedString
import org.identityconnectors.framework.common.exceptions.ConfigurationException
import spock.lang.Specification

/** Bridge from the Scripted REST harness bindings to the library. */
class ScriptSupportSpec extends Specification {

    def "flattens the harness configuration (URI serviceAddress, GuardedString password holding the API key, propertyBag) into properties"() {
        when:
        def props = ScriptSupport.propertiesFrom(ScriptHarness.fakeConfiguration('http://localhost:5276'))

        then:
        props.serviceAddress == 'http://localhost:5276'
        props.apiKey == Fixtures.API_KEY
        !props.containsKey('password')
        !props.containsKey('username')
        props.objectTypes instanceof String
        props.pageSizeDefault == 200
        props.deleteMissingTolerated == true
    }

    def "accepts a String serviceAddress and a String password, returned as apiKey"() {
        when:
        def props = ScriptSupport.propertiesFrom(new Expando(serviceAddress: 'http://h:1', password: 'p', propertyBag: [objectTypes: '{}']))

        then:
        props.serviceAddress == 'http://h:1'
        props.apiKey == 'p'
    }

    def "a GuardedString password is read without leaving a String copy in the configuration object"() {
        given:
        def cfg = ScriptHarness.fakeConfiguration('http://localhost:5276')

        when:
        ScriptSupport.propertiesFrom(cfg)

        then:
        cfg.password instanceof GuardedString
    }

    def "apiKeyHeader in the propertyBag is passed through"() {
        expect:
        ScriptSupport.propertiesFrom(ScriptHarness.fakeConfiguration('http://localhost:5276', [apiKeyHeader: 'X-Custom-Key'])).apiKeyHeader == 'X-Custom-Key'
    }

    def "a propertyBag without objectTypes falls back to parsing customConfiguration"() {
        given:
        def cfg = ScriptHarness.fakeConfiguration('http://localhost:5276')
        cfg.propertyBag = [:]

        when:
        def props = ScriptSupport.propertiesFrom(cfg)

        then:
        props.objectTypes
    }

    def "no objectTypes anywhere is a ConfigurationException"() {
        given:
        def cfg = ScriptHarness.fakeConfiguration('http://localhost:5276')
        cfg.propertyBag = [:]
        cfg.customConfiguration = null

        when:
        ScriptSupport.contextFrom(cfg, null, new CapturingLog().asLog())

        then:
        thrown(ConfigurationException)
    }

    def "contextFrom wires a RestClientEntityApi over the connection binding"() {
        when:
        def ctx = ScriptSupport.contextFrom(ScriptHarness.fakeConfiguration('http://localhost:5276'),
            ScriptHarness.restClient('http://localhost:5276'), new CapturingLog().asLog())

        then:
        ctx.api instanceof RestClientEntityApi
        ctx.config.objectTypes.keySet() == ['user', 'account'] as Set
        ctx.log != null
    }

    def "contextFrom tolerates a null connection for operations that do not need HTTP (schema)"() {
        expect:
        ScriptSupport.contextFrom(ScriptHarness.fakeConfiguration('http://localhost:5276'), null, new CapturingLog().asLog()).config != null
    }
}
