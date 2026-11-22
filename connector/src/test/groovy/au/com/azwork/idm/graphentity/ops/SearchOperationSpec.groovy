package au.com.azwork.idm.entitlementsaccelerator.ops

import au.com.azwork.idm.entitlementsaccelerator.config.ConnectorConfig
import au.com.azwork.idm.entitlementsaccelerator.search.PageCursor
import au.com.azwork.idm.entitlementsaccelerator.support.Fixtures
import org.identityconnectors.common.logging.Log
import org.identityconnectors.framework.common.exceptions.ConnectorIOException
import org.identityconnectors.framework.common.objects.AttributeBuilder
import org.identityconnectors.framework.common.objects.ConnectorObject
import org.identityconnectors.framework.common.objects.Name
import org.identityconnectors.framework.common.objects.OperationOptionsBuilder
import org.identityconnectors.framework.common.objects.Uid
import org.identityconnectors.framework.common.objects.filter.FilterBuilder

/** Design §9.4; decisions P19, P21 — reads by ID, scoped enumeration, filters and cookie paging. */
class SearchOperationSpec extends OperationSpecBase {

    List<ConnectorObject> results = []
    Closure handler = { ConnectorObject o -> results << o; true }

    void seedUser(String id, List<String> archetypes = ['Customer']) {
        service.seed([id: id, baseType: 4, attributes: [name: id], directArchetypes: archetypes])
    }

    // ---------------------------------------------------------------- by ID

    def "get by __UID__ issues GET /admin/entities/{id} and decodes the read model, relationships included"() {
        given:
        service.seed(Fixtures.sanneWire()); service.seed(Fixtures.accountWire())
        service.seedEdge('Sanne', 'Current-Account-1-Sanne', 'Owns')

        when:
        def result = new SearchOperation(ctx()).execute(Fixtures.USER, FilterBuilder.equalTo(new Uid('Sanne')), handler, null)

        then:
        service.requests*.op == ['GET']
        results*.uid*.uidValue == ['Sanne']
        results[0].getAttributeByName('entityRelationships').value == [Fixtures.OWNS_JSON]
        result.pagedResultsCookie == null
    }

    def "get by __NAME__ behaves identically"() {
        given:
        service.seed(Fixtures.sanneWire())

        when:
        new SearchOperation(ctx()).execute(Fixtures.USER, FilterBuilder.equalTo(new Name('Sanne')), handler, null)

        then:
        results*.uid*.uidValue == ['Sanne']
    }

    def "a GET 404 yields an empty result (recon sees an absent target), not an exception"() {
        when:
        def result = new SearchOperation(ctx()).execute(Fixtures.USER, FilterBuilder.equalTo(new Uid('Nobody')), handler, null)

        then:
        results.empty
        result != null
    }

    def "a GET 500 (C1) is a ConnectorIOException, never 'not found', which would make recon create a duplicate"() {
        given:
        service.seed(Fixtures.sanneWire())
        service.failNextWith(500)

        when:
        new SearchOperation(ctx()).execute(Fixtures.USER, FilterBuilder.equalTo(new Uid('Sanne')), handler, null)

        then:
        thrown(ConnectorIOException)
    }

    def "with byIdLookup: search, a read by ID is a search with an EQUALS filter on the ID (P21 fallback)"() {
        given:
        service.seed(Fixtures.sanneWire())

        when:
        new SearchOperation(ctx(byIdLookup: ConnectorConfig.ByIdLookup.SEARCH)).execute(Fixtures.USER, FilterBuilder.equalTo(new Uid('Sanne')), handler, null)

        then:
        service.requests*.op == ['SEARCH']
        service.requestsOf('SEARCH')[0].query.filter == [operation: 'EQUALS', left: 'ID', right: 'Sanne']
        service.requestsOf('SEARCH')[0].query.objectClass == 4
        results*.uid*.uidValue == ['Sanne']
        results[0].getAttributeByName('directArchetypes').value == ['Customer']
    }

    def "a read by ID finds an entity of the right base type even without the scope archetype (P19), so IDM can correlate it"() {
        given:
        service.seed(Fixtures.sanneWire() + [directArchetypes: []])

        when:
        new SearchOperation(ctx()).execute(Fixtures.USER, FilterBuilder.equalTo(new Uid('Sanne')), handler, null)

        then:
        results*.uid*.uidValue == ['Sanne']
    }

    def "a read by ID of an entity with another base type is filtered out"() {
        given:
        service.seed(Fixtures.accountWire())

        when:
        new SearchOperation(ctx()).execute(Fixtures.USER, FilterBuilder.equalTo(new Uid('Current-Account-1-Sanne')), handler, null)

        then:
        results.empty
    }

    // ---------------------------------------------------------------- enumeration

    def "an unfiltered search posts the type's objectClass (4 for subjects, P18) and keeps only its scope archetype (P19)"() {
        given:
        service.seed(Fixtures.sanneWire()); service.seed(Fixtures.accountWire())
        seedUser('Other-User')
        seedUser('Staff-1', ['Employee'])

        when:
        new SearchOperation(ctx()).execute(Fixtures.USER, null, handler, null)

        then:
        service.requestsOf('SEARCH')[0].query.objectClass == 4
        service.requestsOf('SEARCH')[0].query.pageSize == 200
        !service.requestsOf('SEARCH')[0].query.containsKey('filter')
        results*.uid*.uidValue as Set == ['Sanne', 'Other-User'] as Set
    }

    def "enumerated objects carry directArchetypes but no entityRelationships: search items have none (D4, WS10)"() {
        given:
        service.seed(Fixtures.sanneWire())

        when:
        new SearchOperation(ctx()).execute(Fixtures.USER, null, handler, null)

        then:
        results[0].getAttributeByName('directArchetypes').value == ['Customer']
        results[0].getAttributeByName('entityRelationships') == null
    }

    def "the account enumeration posts objectClass 2 and sees only accounts"() {
        given:
        service.seed(Fixtures.sanneWire()); service.seed(Fixtures.accountWire())

        when:
        new SearchOperation(ctx()).execute(Fixtures.ACCOUNT, null, handler, null)

        then:
        service.requestsOf('SEARCH')[0].query.objectClass == 2
        results*.uid*.uidValue == ['Current-Account-1-Sanne']
    }

    def "an attribute filter is sent as the service's filter tree (P21)"() {
        given:
        service.seed(Fixtures.sanneWire())
        seedUser('Other-User')

        when:
        new SearchOperation(ctx()).execute(Fixtures.USER, FilterBuilder.startsWith(AttributeBuilder.build('name', 'Sanne')), handler, null)

        then:
        service.requestsOf('SEARCH')[0].query.filter == [operation: 'STARTSWITH', left: 'name', right: 'Sanne']
        results*.uid*.uidValue == ['Sanne']
    }

    def "paging: a page size smaller than the result set returns an ICF cookie and the remaining count"() {
        given:
        (1..5).each { seedUser("U$it") }

        when:
        def result = new SearchOperation(ctx()).execute(Fixtures.USER, null, handler, new OperationOptionsBuilder().setPageSize(2).build())

        then:
        results.size() == 2
        result.pagedResultsCookie != null
        result.remainingPagedResults == 3
    }

    def "paging: following the cookie yields the rest exactly once"() {
        given:
        (1..5).each { seedUser("U$it") }
        def op = new SearchOperation(ctx())
        def seen = []
        String cookie = null

        when:
        while (true) {
            def page = []
            def ob = new OperationOptionsBuilder().setPageSize(2)
            if (cookie) ob.setPagedResultsCookie(cookie)   // ICF rejects a null cookie
            def r = op.execute(Fixtures.USER, null, { page << it; true }, ob.build())
            seen.addAll(page*.uid*.uidValue)
            cookie = r.pagedResultsCookie
            if (!cookie) break
        }

        then:
        seen.sort() == ['U1', 'U2', 'U3', 'U4', 'U5']
    }

    def "the ICF cookie wraps the service's pagedResultsCookie"() {
        given:
        (1..3).each { seedUser("U$it") }

        when:
        def r = new SearchOperation(ctx()).execute(Fixtures.USER, null, handler, new OperationOptionsBuilder().setPageSize(1).build())

        then:
        PageCursor.decode(r.pagedResultsCookie).serviceCookie == '1'
        service.requestsOf('SEARCH')[0].query.pageSize == 1
    }

    def "without a page size every page is streamed through the handler, following the cookie, and no cookie is returned"() {
        given:
        (1..7).each { seedUser("U$it") }

        when:
        def result = new SearchOperation(ctx(pageSizeDefault: 3)).execute(Fixtures.USER, null, handler, null)

        then:
        results.size() == 7
        service.requestsOf('SEARCH')*.query*.pagedResultsCookie == [null, '3', '6']
        result.pagedResultsCookie == null
    }

    def "scoping happens after paging, so a page can hand fewer objects to the handler; the cookie still reflects the service's page"() {
        given:
        seedUser('A-Staff', ['Employee'])
        (1..3).each { seedUser("U$it") }

        when:
        def result = new SearchOperation(ctx()).execute(Fixtures.USER, null, handler, new OperationOptionsBuilder().setPageSize(2).build())

        then:
        results*.uid*.uidValue == ['U1']
        result.pagedResultsCookie != null
    }

    def "the handler returning false stops enumeration early"() {
        given:
        (1..5).each { seedUser("U$it") }

        when:
        new SearchOperation(ctx(pageSizeDefault: 2)).execute(Fixtures.USER, null, { results << it; false }, null)

        then:
        results.size() == 1
        service.requestsOf('SEARCH').size() == 1
    }

    def "an ICF ResultsHandler is accepted as well as a Closure"() {
        given:
        (1..2).each { seedUser("U$it") }
        def seen = []

        when:
        new SearchOperation(ctx()).execute(Fixtures.USER, null, { ConnectorObject o -> seen << o; true } as org.identityconnectors.framework.common.objects.ResultsHandler, null)

        then:
        seen*.uid*.uidValue == ['U1', 'U2']
    }

    def "a 5xx from the search endpoint → ConnectorIOException"() {
        given:
        service.failNextWith(502)

        when:
        new SearchOperation(ctx()).execute(Fixtures.USER, null, handler, null)

        then:
        thrown(ConnectorIOException)
    }

    def "an unsupported filter is reported as unsupported, before any request"() {
        when:
        new SearchOperation(ctx()).execute(Fixtures.USER, FilterBuilder.contains(new Name('S')), handler, null)

        then:
        def e = thrown(UnsupportedOperationException)
        e.message.toLowerCase().contains('filter')
        service.requests.empty
    }

    def "a malformed search item (no uid or name) is skipped with one WARN rather than aborting the whole recon"() {
        given:
        service.failNextWith(200, [results: [[baseType: 'Subject', directArchetypes: ['Customer']], Fixtures.sanneSearchItem()],
                                   pagedResultsCookie: null, totalResults: 2, hasMore: false])

        when:
        new SearchOperation(ctx()).execute(Fixtures.USER, null, handler, null)

        then:
        results*.uid*.uidValue == ['Sanne']
        log.messagesAt(Log.Level.WARN).size() == 1
    }

    def "a corrupt cookie is rejected"() {
        when:
        new SearchOperation(ctx()).execute(Fixtures.USER, null, handler, new OperationOptionsBuilder().setPagedResultsCookie('junk!').build())

        then:
        thrown(IllegalArgumentException)
    }
}
