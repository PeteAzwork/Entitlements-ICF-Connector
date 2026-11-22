package au.com.azwork.idm.entitlementsaccelerator.integration

import au.com.azwork.idm.entitlementsaccelerator.support.FakeGraphService
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Stepwise
import spock.util.concurrent.PollingConditions

/** WS8.08 and WS8.09 in IDM 7.5: the mapping library module's lifetime, and 429s under queued sync. */
@Stepwise
class RuntimeInIdmSpec extends Specification {

    static final String LIB = "require('lib/entitlementsacceleratorPayload')"

    @Shared IdmStack stack = IdmStack.instance
    PollingConditions poll = new PollingConditions(timeout: 60, delay: 1)

    IdmClient getIdm() { stack.idm }
    FakeGraphService getService() { stack.service }

    Object eval(String source) { idm.call('POST', 'script?_action=eval', [type: 'text/javascript', globals: [:], source: source]) }

    def "IDM loads the required module afresh for each script evaluation, so the library's cache lasts one evaluation (WS8.08)"() {
        when: 'one evaluation loads the configuration, and a separate one asks when that happened'
        long loaded = eval("var lib = ${LIB}; lib.loadTypeConfig('user'); lib.cacheInfo().loadedAt;") as long
        long seenLater = eval("${LIB}.cacheInfo().loadedAt;") as long

        then: 'the second evaluation has a fresh module, with nothing cached'
        loaded > 0
        seenLater == 0
    }

    def "within one reconciliation, each entity's transforms load the configuration themselves (WS8.08)"() {
        given: 'a transform that reports when the cache was loaded, on five users'
        stack.setMappings(false)
        Map mapping = stack.mappings.find { it.name == 'managedUser_systementitlementsacceleratorUser' }
        List props = (mapping.properties as List).findAll { it.target != 'description' } +
            [[source: '', target: 'description', transform: [type: 'text/javascript',
              source: "var lib = require('lib/entitlementsacceleratorPayload'); lib.loadTypeConfig('user'); String(lib.cacheInfo().loadedAt);"]]]
        idm.put('config/sync', [mappings: stack.mappings.collect { it.name == mapping.name ? it + [properties: props, enableSync: false] : it + [enableSync: false] }])
        Thread.sleep(5000)
        (1..5).each { idm.create('managed/user', [userName: "Cache-${it}".toString(), givenName: 'C', sn: "${it}".toString(), mail: "c${it}@example.com".toString()]) }

        when:
        idm.recon('managedUser_systementitlementsacceleratorUser')
        List values = (1..5).collect { service.entities["Cache-${it}".toString()]?.attributes?.description }

        then: 'every entity carries its own load time: nothing was shared between them'
        values.every { it }
        values.toSet().size() == 5

        cleanup:
        stack.setMappings(true)
    }

    def "under queued sync, updates the service answers with 429 are retried by IDM until they succeed (WS8.09)"() {
        given: 'queued sync on the user mapping, and a user in the service'
        stack.setMappings(true, [managedUser_systementitlementsacceleratorUser: [queuedSync: [enabled: true, pageSize: 100, pollingInterval: 1000,
                                                                                 maxRetries: 5, retryDelay: 2000, postRetryAction: 'logged-ignore']]])
        String id = idm.create('managed/user', [userName: 'Queued-1', givenName: 'Q', sn: 'One', mail: 'q1@example.com'])._id
        poll.eventually { assert service.entities.containsKey('Queued-1') }

        when: 'the service rate-limits the next two updates'
        service.failNextOf('PUT', 429, [title: 'Too Many Requests', status: 429], ['Retry-After': '1'])
        service.failNextOf('PUT', 429, [title: 'Too Many Requests', status: 429], ['Retry-After': '1'])
        idm.patch("managed/user/${id}", [[operation: 'replace', field: 'givenName', value: 'Retried']])

        then: 'the change arrives after IDM retries, and each refusal was logged with the Retry-After hint'
        poll.eventually { assert service.entities['Queued-1'].attributes.givenName == 'Retried' }
        service.requestsOf('PUT').count { it.id == 'Queued-1' } >= 3
        stack.log().contains('HTTP 429 during UPDATE')

        cleanup:
        stack.setMappings(true)
    }
}
