package au.com.azwork.idm.graphentity.ops

import au.com.azwork.idm.graphentity.search.PageCursor
import au.com.azwork.idm.graphentity.support.Fixtures
import org.identityconnectors.framework.common.exceptions.ConnectorIOException
import org.identityconnectors.framework.common.objects.AttributeBuilder
import org.identityconnectors.framework.common.objects.ConnectorObject
import org.identityconnectors.framework.common.objects.Name
import org.identityconnectors.framework.common.objects.OperationOptionsBuilder
import org.identityconnectors.framework.common.objects.Uid
import org.identityconnectors.framework.common.objects.filter.FilterBuilder

/** Design §9.4 — search / get, type scoping and paging. */
class SearchOperationSpec extends OperationSpecBase {

    List<ConnectorObject> results = []
    Closure handler = { ConnectorObject o -> results << o; true }

    def "get by __UID__ issues GET /admin/entities/{id} and hands back one decoded object"() {
        given:
        service.seed(Fixtures.sanneWire())

        when:
        def result = new SearchOperation(ctx()).execute(Fixtures.USER, FilterBuilder.equalTo(new Uid('Sanne')), handler, null)

        then:
        service.requests*.op == ['GET']
        results*.uid*.uidValue == ['Sanne']
        results[0].objectClass == Fixtures.USER
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

    def "a GET of an entity that exists but belongs to another type is filtered out"() {
        given:
        service.seed(Fixtures.accountWire())

        when:
        new SearchOperation(ctx()).execute(Fixtures.USER, FilterBuilder.equalTo(new Uid('Current-Account-1-Sanne')), handler, null)

        then:
        results.empty
    }

    def "the decoded object re-serialises relationships so recon can compare edge state"() {
        given:
        service.seed(Fixtures.sanneWire()); service.seed(Fixtures.accountWire())
        service.seedEdge('Sanne', 'Current-Account-1-Sanne', 'Owns')

        when:
        new SearchOperation(ctx()).execute(Fixtures.ACCOUNT, FilterBuilder.equalTo(new Uid('Current-Account-1-Sanne')), handler, null)

        then:
        results[0].getAttributeByName('entityRelationships').value == ['{"SourceID":"Sanne","type":"Owns"}']
        results[0].getAttributeByName('lastUpdated') != null
    }

    def "an unfiltered search enumerates from epoch, scoped to the requesting type"() {
        given:
        service.seed(Fixtures.sanneWire()); service.seed(Fixtures.accountWire())
        service.seed([ID: 'Other-User', BaseType: 4, attributes: [ID: 'Other-User'], directArchetypes: ['Customer']])

        when:
        new SearchOperation(ctx()).execute(Fixtures.USER, null, handler, null)

        then:
        service.requestsOf('SEARCH')[0].query.updatedAfter == '1970-01-01T00:00:00Z'
        results*.uid*.uidValue as Set == ['Sanne', 'Other-User'] as Set
    }

    def "the account enumeration sees only accounts"() {
        given:
        service.seed(Fixtures.sanneWire()); service.seed(Fixtures.accountWire())

        when:
        new SearchOperation(ctx()).execute(Fixtures.ACCOUNT, null, handler, null)

        then:
        results*.uid*.uidValue == ['Current-Account-1-Sanne']
    }

    def "the configured timestampSearchParam name is used on the wire"() {
        given:
        service.timestampParam = 'modifiedSince'

        when:
        new SearchOperation(ctx(timestampSearchParam: 'modifiedSince')).execute(Fixtures.USER, null, handler, null)

        then:
        service.requestsOf('SEARCH')[0].query.containsKey('modifiedSince')
    }

    def "GreaterThan on lastUpdated passes the instant through to the timestamp search"() {
        when:
        new SearchOperation(ctx()).execute(Fixtures.USER,
            FilterBuilder.greaterThan(AttributeBuilder.build('lastUpdated', '2026-07-06T00:00:00Z')), handler, null)

        then:
        service.requestsOf('SEARCH')[0].query.updatedAfter == '2026-07-06T00:00:00Z'
    }

    def "paging: a page size smaller than the result set returns a cookie and the remaining count"() {
        given:
        (1..5).each { service.seed([ID: "U$it", BaseType: 4, attributes: [ID: "U$it"], directArchetypes: ['Customer']]) }

        when:
        def result = new SearchOperation(ctx()).execute(Fixtures.USER, null, handler, new OperationOptionsBuilder().setPageSize(2).build())

        then:
        results.size() == 2
        result.pagedResultsCookie != null
        result.remainingPagedResults == 3
    }

    def "paging: following the cookie yields the rest exactly once"() {
        given:
        (1..5).each { service.seed([ID: "U$it", BaseType: 4, attributes: [ID: "U$it"], directArchetypes: ['Customer']]) }
        def op = new SearchOperation(ctx())
        def seen = []
        String cookie = null

        when:
        while (true) {
            def page = []
            def r = op.execute(Fixtures.USER, null, { page << it; true }, new OperationOptionsBuilder().setPageSize(2).setPagedResultsCookie(cookie).build())
            seen.addAll(page*.uid*.uidValue)
            cookie = r.pagedResultsCookie
            if (!cookie) break
        }

        then:
        seen.sort() == ['U1', 'U2', 'U3', 'U4', 'U5']
    }

    def "paging without a page size streams every page through the handler and returns no cookie"() {
        given:
        (1..7).each { service.seed([ID: "U$it", BaseType: 4, attributes: [ID: "U$it"], directArchetypes: ['Customer']]) }

        when:
        def result = new SearchOperation(ctx(pageSizeDefault: 3)).execute(Fixtures.USER, null, handler, null)

        then:
        results.size() == 7
        service.requestsOf('SEARCH').size() == 3
        result.pagedResultsCookie == null
    }

    def "type scoping happens after paging, so a page of mixed types is not under-filled silently: remaining count still reported"() {
        given:
        service.seed(Fixtures.accountWire())
        (1..3).each { service.seed([ID: "U$it", BaseType: 4, attributes: [ID: "U$it"], directArchetypes: ['Customer']]) }

        when:
        def result = new SearchOperation(ctx()).execute(Fixtures.USER, null, handler, new OperationOptionsBuilder().setPageSize(2).build())

        then:
        results.every { it.objectClass == Fixtures.USER }
        result.pagedResultsCookie != null
    }

    def "the handler returning false stops enumeration early"() {
        given:
        (1..5).each { service.seed([ID: "U$it", BaseType: 4, attributes: [ID: "U$it"], directArchetypes: ['Customer']]) }

        when:
        new SearchOperation(ctx(pageSizeDefault: 2)).execute(Fixtures.USER, null, { results << it; false }, null)

        then:
        results.size() == 1
        service.requestsOf('SEARCH').size() == 1
    }

    def "a 5xx from the search endpoint → ConnectorIOException"() {
        given:
        service.failNextWith(502)

        when:
        new SearchOperation(ctx()).execute(Fixtures.USER, null, handler, null)

        then:
        thrown(ConnectorIOException)
    }

    def "an unsupported filter is reported as unsupported"() {
        when:
        new SearchOperation(ctx()).execute(Fixtures.USER, FilterBuilder.contains(new Name('S')), handler, null)

        then:
        def e = thrown(UnsupportedOperationException)
        e.message.toLowerCase().contains('filter')
        service.requests.empty
    }

    def "a malformed remote document (no ID) is skipped with a WARN rather than aborting the whole recon"() {
        given:
        service.seed(Fixtures.sanneWire())
        service.failNextWith(200, [items: [[BaseType: 4, directArchetypes: ['Customer']], Fixtures.sanneWire()], totalCount: 2])

        when:
        new SearchOperation(ctx()).execute(Fixtures.USER, null, handler, null)

        then:
        results*.uid*.uidValue == ['Sanne']
        log.messagesAt(org.identityconnectors.common.logging.Log.Level.WARN).size() == 1
    }

    def "a corrupt cookie is rejected"() {
        when:
        new SearchOperation(ctx()).execute(Fixtures.USER, null, handler, new OperationOptionsBuilder().setPagedResultsCookie('junk').build())

        then:
        thrown(IllegalArgumentException)
    }

    def "cookies are produced by PageCursor so they round-trip"() {
        given:
        (1..3).each { service.seed([ID: "U$it", BaseType: 4, attributes: [ID: "U$it"], directArchetypes: ['Customer']]) }

        when:
        def r = new SearchOperation(ctx()).execute(Fixtures.USER, null, handler, new OperationOptionsBuilder().setPageSize(1).build())

        then:
        PageCursor.decode(r.pagedResultsCookie).offset == 1
    }
}
