package au.com.azwork.idm.graphentity.scripts

import au.com.azwork.idm.graphentity.support.CapturingLog
import au.com.azwork.idm.graphentity.support.Fixtures
import au.com.azwork.idm.graphentity.support.ScriptHarness
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import groovy.json.JsonOutput
import org.identityconnectors.framework.common.exceptions.InvalidCredentialException
import org.identityconnectors.framework.common.exceptions.UnknownUidException
import org.identityconnectors.framework.common.objects.ConnectorObject
import org.identityconnectors.framework.common.objects.OperationOptionsBuilder
import org.identityconnectors.framework.common.objects.Schema
import org.identityconnectors.framework.common.objects.SearchResult
import org.identityconnectors.framework.common.objects.Uid
import org.identityconnectors.framework.common.objects.filter.FilterBuilder
import spock.lang.Shared
import spock.lang.Specification

import static com.github.tomakehurst.wiremock.client.WireMock.*

/**
 * Design §4 — the seven ICF scripts, evaluated exactly as the Scripted REST harness evaluates them
 * (GroovyShell + bindings), against WireMock. These are the tests that catch a script that compiles but
 * binds the wrong variable name, returns the wrong type, or forgets to return at all.
 */
class OperationScriptsSpec extends Specification {

    @Shared WireMockServer wm = new WireMockServer(WireMockConfiguration.options().dynamicPort())
    CapturingLog log = new CapturingLog()
    String baseUrl

    def setupSpec() { wm.start() }
    def cleanupSpec() { wm.stop() }
    def setup() { wm.resetAll(); baseUrl = "http://localhost:${wm.port()}" }

    Map<String, Object> bindings(Map extra = [:]) {
        ScriptHarness.baseBindings(baseUrl, log.asLog()) + extra
    }

    def "every script in the design's table exists"() {
        expect:
        ['CustomizerScript', 'TestScript', 'SchemaScript', 'SearchScript', 'CreateScript', 'UpdateScript', 'DeleteScript'].every {
            new File(ScriptHarness.SCRIPT_DIR, "${it}.groovy").isFile()
        }
    }

    def "there is deliberately no SyncScript (§4)"() {
        expect:
        !new File(ScriptHarness.SCRIPT_DIR, 'SyncScript.groovy').exists()
    }

    def "TestScript: 200 from the search endpoint passes"() {
        given:
        wm.stubFor(get(urlPathEqualTo('/admin/entities')).willReturn(okJson('{"items":[],"totalCount":0}')))

        when:
        ScriptHarness.run('TestScript.groovy', bindings())

        then:
        noExceptionThrown()
        wm.verify(getRequestedFor(urlPathEqualTo('/admin/entities')).withQueryParam('pageSize', equalTo('1')))
    }

    def "TestScript: 401 → InvalidCredentialException"() {
        given:
        wm.stubFor(get(urlPathEqualTo('/admin/entities')).willReturn(aResponse().withStatus(401)))

        when:
        ScriptHarness.run('TestScript.groovy', bindings())

        then:
        thrown(InvalidCredentialException)
    }

    def "SchemaScript returns a Schema built from the objectTypes in the propertyBag"() {
        when:
        def schema = ScriptHarness.run('SchemaScript.groovy', bindings(connectorClass: org.identityconnectors.framework.spi.Connector))

        then:
        schema instanceof Schema
        schema.objectClassInfo*.type as Set == ['user', 'account'] as Set
    }

    def "CreateScript POSTs, verifies, and returns a Uid"() {
        given:
        wm.stubFor(post(urlEqualTo('/admin/entities/')).willReturn(aResponse().withStatus(201)))
        wm.stubFor(get(urlEqualTo('/admin/entities/Sanne')).willReturn(okJson(JsonOutput.toJson(Fixtures.sanneWire() + [Relationships: []]))))

        when:
        def uid = ScriptHarness.run('CreateScript.groovy', bindings(objectClass: Fixtures.USER, attributes: Fixtures.sanneAttributes(), options: null))

        then:
        uid instanceof Uid
        uid.uidValue == 'Sanne'
        wm.verify(postRequestedFor(urlEqualTo('/admin/entities/')).withRequestBody(equalToJson(JsonOutput.toJson(Fixtures.sanneWire()))))
        log.messagesAt(org.identityconnectors.common.logging.Log.Level.WARN) == ['dropped edge Sanne -Owns-> Current-Account-1-Sanne']
    }

    def "UpdateScript PUTs the full document and returns the Uid"() {
        given:
        wm.stubFor(put(urlEqualTo('/admin/entities/Sanne')).willReturn(ok()))

        when:
        def uid = ScriptHarness.run('UpdateScript.groovy', bindings(objectClass: Fixtures.USER, uid: new Uid('Sanne'),
            attributes: Fixtures.sanneAttributes(name: 'Sanne Smith'), options: null))

        then:
        uid.uidValue == 'Sanne'
        wm.verify(putRequestedFor(urlEqualTo('/admin/entities/Sanne'))
            .withRequestBody(matchingJsonPath('$.attributes.name', equalTo('Sanne Smith')))
            .withRequestBody(matchingJsonPath('$.Relationships[0].TargetID', equalTo('Current-Account-1-Sanne'))))
        wm.verify(0, getRequestedFor(urlPathMatching('/admin/entities/.*')))
    }

    def "UpdateScript: 404 → UnknownUidException"() {
        given:
        wm.stubFor(put(urlEqualTo('/admin/entities/Sanne')).willReturn(aResponse().withStatus(404)))

        when:
        ScriptHarness.run('UpdateScript.groovy', bindings(objectClass: Fixtures.USER, uid: new Uid('Sanne'), attributes: Fixtures.sanneAttributes(), options: null))

        then:
        thrown(UnknownUidException)
    }

    def "DeleteScript DELETEs and tolerates 404 by default"() {
        given:
        wm.stubFor(delete(urlEqualTo('/admin/entities/Sanne')).willReturn(aResponse().withStatus(404)))

        when:
        ScriptHarness.run('DeleteScript.groovy', bindings(objectClass: Fixtures.USER, uid: new Uid('Sanne'), options: null))

        then:
        noExceptionThrown()
        wm.verify(deleteRequestedFor(urlEqualTo('/admin/entities/Sanne')))
    }

    def "DeleteScript honours deleteMissingTolerated=false from the propertyBag"() {
        given:
        wm.stubFor(delete(urlEqualTo('/admin/entities/Sanne')).willReturn(aResponse().withStatus(404)))
        def b = ScriptHarness.baseBindings(baseUrl, log.asLog(), [deleteMissingTolerated: false])

        when:
        ScriptHarness.run('DeleteScript.groovy', b + [objectClass: Fixtures.USER, uid: new Uid('Sanne'), options: null])

        then:
        thrown(UnknownUidException)
    }

    def "SearchScript by uid calls the handler closure once and returns a SearchResult"() {
        given:
        wm.stubFor(get(urlEqualTo('/admin/entities/Sanne')).willReturn(okJson(JsonOutput.toJson(Fixtures.sanneWire()))))
        def seen = []

        when:
        def result = ScriptHarness.run('SearchScript.groovy', bindings(objectClass: Fixtures.USER, filter: FilterBuilder.equalTo(new Uid('Sanne')),
            handler: { ConnectorObject o -> seen << o; true }, options: new OperationOptionsBuilder().build()))

        then:
        result instanceof SearchResult
        seen*.uid*.uidValue == ['Sanne']
    }

    def "SearchScript unfiltered enumerates with the configured timestamp parameter and page size"() {
        given:
        wm.stubFor(get(urlPathEqualTo('/admin/entities')).willReturn(okJson(JsonOutput.toJson(
            [items: [Fixtures.sanneWire(), Fixtures.accountWire()], totalCount: 2]))))
        def seen = []

        when:
        ScriptHarness.run('SearchScript.groovy', bindings(objectClass: Fixtures.USER, filter: null,
            handler: { seen << it; true }, options: new OperationOptionsBuilder().build()))

        then:
        seen*.uid*.uidValue == ['Sanne']
        wm.verify(getRequestedFor(urlPathEqualTo('/admin/entities'))
            .withQueryParam('updatedAfter', equalTo('1970-01-01T00:00:00Z'))
            .withQueryParam('pageSize', equalTo('200')))
    }

    def "scripts never log the password"() {
        given:
        wm.stubFor(get(urlPathEqualTo('/admin/entities')).willReturn(aResponse().withStatus(500).withBody('{"error":"x"}')))

        when:
        try { ScriptHarness.run('TestScript.groovy', bindings()) } catch (ignored) {}

        then:
        !log.allText().contains('secret')
    }
}
