package au.com.azwork.idm.entitlementsaccelerator.integration

import au.com.azwork.idm.entitlementsaccelerator.support.FakeGraphHttpServer
import au.com.azwork.idm.entitlementsaccelerator.support.FakeGraphService
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * The network settings of docs/deployment-guide.md §5, in IDM 7.5: https to a service whose certificate comes from a
 * private CA, and an outbound proxy. The CA is imported into IDM's trust store before IDM starts ({@link TestPki}).
 */
class NetworkInIdmSpec extends Specification {

    @Shared IdmStack stack = IdmStack.instance
    @Shared @AutoCleanup FakeGraphHttpServer trusted = https(stack.service, 'trusted')
    @Shared FakeGraphService otherService = new FakeGraphService()
    @Shared @AutoCleanup FakeGraphHttpServer untrusted = https(otherService, 'untrusted')
    @Shared @AutoCleanup FakeGraphHttpServer wrongHost = https(otherService, 'wrongHost')
    @Shared @AutoCleanup ForwardProxy proxy = new ForwardProxy()

    IdmClient getIdm() { stack.idm }

    def cleanup() {
        stack.loadProvisioner(stack.provisioner)   // back to the plain-http service, no proxy
    }

    def "https to a certificate from a private CA in IDM's trust store: the Test passes and entities are read"() {
        given:
        stack.saveProvisioner([configurationProperties: [serviceAddress: "https://localhost:${trusted.port}".toString()]])

        expect:
        idm.action('system/entitlementsaccelerator', 'test').ok == true
        idm.get('system/entitlementsaccelerator/user?_queryFilter=true&_pageSize=1') != null
    }

    def "a certificate from a CA IDM does not trust: the Test fails on the certificate path, and nothing reaches the service"() {
        given:
        stack.saveProvisioner([configurationProperties: [serviceAddress: "https://localhost:${untrusted.port}".toString()]])

        expect:
        idm.action('system/entitlementsaccelerator', 'test').ok == false
        stack.log().contains('unable to find valid certification path')
        otherService.requests.isEmpty()
    }

    def "a trusted certificate for another host name: the Test fails on the host name, and nothing reaches the service"() {
        given:
        stack.saveProvisioner([configurationProperties: [serviceAddress: "https://localhost:${wrongHost.port}".toString()]])

        expect:
        idm.action('system/entitlementsaccelerator', 'test').ok == false
        stack.log() =~ /doesn't match any of the subject alternative names|SSLPeerUnverifiedException/
        otherService.requests.isEmpty()
    }

    def "proxyAddress sends every request through the proxy, the API key with it"() {
        given:
        proxy.forwarded.clear()
        stack.saveProvisioner([configurationProperties: [proxyAddress: proxy.address]])

        expect:
        idm.action('system/entitlementsaccelerator', 'test').ok == true
        proxy.forwarded.any { it.startsWith("POST ${stack.server.baseUrl}/admin/entities/search") }
    }

    private static FakeGraphHttpServer https(FakeGraphService service, String certificate) {
        new FakeGraphHttpServer(service, IdmStack.API_KEY, FakeGraphHttpServer.DEFAULT_API_KEY_HEADER,
            new InetSocketAddress(InetAddress.loopbackAddress, 0), IdmStack.instance.pki.serverContext(certificate)).start()
    }
}
