package au.com.azwork.idm.entitlementsaccelerator.scripts

import au.com.azwork.idm.entitlementsaccelerator.support.CapturingLog
import au.com.azwork.idm.entitlementsaccelerator.support.FakeSchemaBuilder
import au.com.azwork.idm.entitlementsaccelerator.support.Fixtures
import au.com.azwork.idm.entitlementsaccelerator.support.ScriptHarness
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
import spock.lang.Unroll

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

    def "TestScript: a one-item search and a manifest that defines the configured archetypes pass (§9.5, P22)"() {
        given:
        wm.stubFor(post(urlEqualTo('/admin/entities/search')).willReturn(okJson('{"results":[],"pagedResultsCookie":null,"totalResults":0,"hasMore":false}')))
        wm.stubFor(get(urlEqualTo('/admin/manifests')).willReturn(okJson('[{"name":"test","subjectArchetypes":["Customer"],"resourceArchetypes":["Account"]}]')))

        when:
        ScriptHarness.run('TestScript.groovy', bindings())

        then:
        noExceptionThrown()
        wm.verify(postRequestedFor(urlEqualTo('/admin/entities/search')).withRequestBody(matchingJsonPath('$.pageSize', equalTo('1'))))
        wm.verify(getRequestedFor(urlEqualTo('/admin/manifests')))
    }

    def "TestScript: 401 → InvalidCredentialException"() {
        given:
        wm.stubFor(post(urlEqualTo('/admin/entities/search')).willReturn(aResponse().withStatus(401)))

        when:
        ScriptHarness.run('TestScript.groovy', bindings())

        then:
        thrown(InvalidCredentialException)
    }

    def "SchemaScript defines the schema through the harness's builder DSL (WS3.01), with no connectorClass binding"() {
        given:
        def builder = new FakeSchemaBuilder()

        when:
        def schema = ScriptHarness.run('SchemaScript.groovy', bindings(builder: builder))

        then:
        builder.objectClasses*.type as Set == ['user', 'account'] as Set
        builder.objectClasses.find { it.type == 'user' }.attributes*.name as Set ==
            ['__NAME__', 'name', 'mail', 'telephoneNumber', 'directArchetypes', 'entityRelationships', 'lastEvaluated'] as Set
        builder.objectClasses.every { it.disabled*.simpleName as Set == ['SyncOp', 'AuthenticateOp', 'ResolveUsernameOp'] as Set }
        schema instanceof Schema   // also returned; the harness ignores it
        schema.objectClassInfo*.type as Set == ['user', 'account'] as Set
        wm.allServeEvents.empty
    }

    def "CreateScript POSTs the EntityDTO to /admin/entities, verifies with a GET of the read model, and returns a Uid"() {
        given:
        wm.stubFor(post(urlEqualTo('/admin/entities')).willReturn(aResponse().withStatus(200)))
        wm.stubFor(get(urlEqualTo('/admin/entities/Sanne')).willReturn(okJson(JsonOutput.toJson(Fixtures.sanneEntity() + [relationships: []]))))

        when:
        def uid = ScriptHarness.run('CreateScript.groovy', bindings(objectClass: Fixtures.USER, attributes: Fixtures.sanneAttributes(), options: null))

        then:
        uid instanceof Uid
        uid.uidValue == 'Sanne'
        wm.verify(postRequestedFor(urlEqualTo('/admin/entities')).withRequestBody(equalToJson(JsonOutput.toJson(Fixtures.sanneWire()))))
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
            .withRequestBody(matchingJsonPath('$.relationships[0].sourceID', equalTo('Sanne')))
            .withRequestBody(matchingJsonPath('$.relationships[0].targetID', equalTo('Current-Account-1-Sanne'))))
        wm.verify(0, getRequestedFor(urlPathMatching('/admin/entities/.*')))
    }

    def "CreateScript takes the name from the harness's id binding when __NAME__ is not among the attributes (WS3.01)"() {
        given:
        wm.stubFor(post(urlEqualTo('/admin/entities')).willReturn(aResponse().withStatus(200)))
        wm.stubFor(get(urlEqualTo('/admin/entities/Sanne')).willReturn(okJson(JsonOutput.toJson(Fixtures.sanneEntity()))))

        when:
        def uid = ScriptHarness.run('CreateScript.groovy', bindings(objectClass: Fixtures.USER, id: 'Sanne',
            attributes: Fixtures.sanneAttributes(__NAME__: null), options: null))

        then:
        uid.uidValue == 'Sanne'
        wm.verify(postRequestedFor(urlEqualTo('/admin/entities')).withRequestBody(matchingJsonPath('$.id', equalTo('Sanne'))))
    }

    def "UpdateScript with operation UPDATE proceeds as a full replacement"() {
        given:
        wm.stubFor(put(urlEqualTo('/admin/entities/Sanne')).willReturn(ok()))

        when:
        def uid = ScriptHarness.run('UpdateScript.groovy', bindings(operation: 'UPDATE', objectClass: Fixtures.USER, uid: new Uid('Sanne'),
            attributes: Fixtures.sanneAttributes(), options: null))

        then:
        uid.uidValue == 'Sanne'
    }

    @Unroll
    def "UpdateScript refuses #operation: a value delta turned into a PUT would delete what it omits (WS3.10, P15)"() {
        when:
        ScriptHarness.run('UpdateScript.groovy', bindings(operation: operation, objectClass: Fixtures.USER, uid: new Uid('Sanne'),
            attributes: Fixtures.sanneAttributes(), options: null))

        then:
        thrown(UnsupportedOperationException)
        wm.allServeEvents.empty

        where:
        operation << ['ADD_ATTRIBUTE_VALUES', 'REMOVE_ATTRIBUTE_VALUES']
    }

    def "the scripts authenticate through the customizer's client: the decorated client sends the key (WS3.01)"() {
        given:
        wm.stubFor(delete(urlEqualTo('/admin/entities/Sanne')).willReturn(ok()))

        when:
        ScriptHarness.run('DeleteScript.groovy', bindings(objectClass: Fixtures.USER, uid: new Uid('Sanne'), options: null))

        then:
        wm.verify(deleteRequestedFor(urlEqualTo('/admin/entities/Sanne'))
            .withHeader('X-API-KEY', equalTo(Fixtures.API_KEY)).withoutHeader('Authorization'))
    }

    def "without a decorated client the scripts wrap the harness's HttpClient themselves, keeping its key header"() {
        given:
        wm.stubFor(delete(urlEqualTo('/admin/entities/Sanne')).willReturn(ok()))
        def b = bindings(objectClass: Fixtures.USER, uid: new Uid('Sanne'), options: null)
        b.remove('customizedConnection')

        when:
        ScriptHarness.run('DeleteScript.groovy', b)

        then:
        wm.verify(deleteRequestedFor(urlEqualTo('/admin/entities/Sanne')).withHeader('X-API-KEY', equalTo(Fixtures.API_KEY)))
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
        wm.stubFor(get(urlEqualTo('/admin/entities/Sanne')).willReturn(okJson(JsonOutput.toJson(Fixtures.sanneEntity()))))
        def seen = []

        when:
        def result = ScriptHarness.run('SearchScript.groovy', bindings(objectClass: Fixtures.USER, filter: FilterBuilder.equalTo(new Uid('Sanne')),
            handler: { ConnectorObject o -> seen << o; true }, options: new OperationOptionsBuilder().build()))

        then:
        result instanceof SearchResult
        seen*.uid*.uidValue == ['Sanne']
    }

    def "SearchScript unfiltered enumerates with the type's objectClass and the page size in the POST body"() {
        given:
        wm.stubFor(post(urlEqualTo('/admin/entities/search')).willReturn(okJson(JsonOutput.toJson(
            [results: [Fixtures.sanneSearchItem()], pagedResultsCookie: null, totalResults: 1, hasMore: false]))))
        def seen = []

        when:
        ScriptHarness.run('SearchScript.groovy', bindings(objectClass: Fixtures.USER, filter: null,
            handler: { seen << it; true }, options: new OperationOptionsBuilder().build()))

        then:
        seen*.uid*.uidValue == ['Sanne']
        wm.verify(postRequestedFor(urlEqualTo('/admin/entities/search'))
            .withRequestBody(matchingJsonPath('$.objectClass', equalTo('4')))
            .withRequestBody(matchingJsonPath('$.pageSize', equalTo('200'))))
    }

    def "scripts never log the API key"() {
        given:
        wm.stubFor(post(urlEqualTo('/admin/entities/search')).willReturn(aResponse().withStatus(500)
            .withHeader('Content-Type', 'application/problem+json').withBody('{"title":"x","status":500}')))

        when:
        try { ScriptHarness.run('TestScript.groovy', bindings()) } catch (ignored) {}

        then:
        !log.allText().contains(Fixtures.API_KEY)
    }
}
