package au.com.azwork.idm.entitlementsaccelerator.search

import au.com.azwork.idm.entitlementsaccelerator.http.HttpResponse
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Design §9.4 — paging through the service's opaque {@code pagedResultsCookie}, wrapped in ICF's paged-results
 * cookie. All paging knowledge stays in PageCursor (P6 superseded by the real protocol).
 */
class PageCursorSpec extends Specification {

    static HttpResponse page(Map body) { new HttpResponse(status: 200, body: body) }

    def "a null or blank ICF cookie is the first page"() {
        expect:
        PageCursor.decode(null).serviceCookie == null
        PageCursor.decode('').serviceCookie == null
        PageCursor.decode('  ').serviceCookie == null
    }

    def "encode/decode round-trips the service's cookie"() {
        expect:
        PageCursor.decode(new PageCursor(serviceCookie: '100').encode()).serviceCookie == '100'
        PageCursor.decode(new PageCursor(serviceCookie: 'opaque=+/token').encode()).serviceCookie == 'opaque=+/token'
    }

    def "the ICF cookie also carries how many results came before it, so a paged search can report what remains"() {
        expect:
        PageCursor.decode(new PageCursor(serviceCookie: '100', consumed: 40).encode()).consumed == 40
        PageCursor.decode(new PageCursor(serviceCookie: '100').encode()).consumed == 0
    }

    def "a cookie with an invalid count is rejected"() {
        when:
        PageCursor.decode(Base64.urlEncoder.encodeToString('{"c":"1","n":-3}'.bytes))

        then:
        thrown(IllegalArgumentException)
    }

    def "the ICF cookie is URL-safe"() {
        expect:
        new PageCursor(serviceCookie: 'opaque=+/token').encode() ==~ /[A-Za-z0-9_-]+=*/
    }

    @Unroll
    def "a corrupt ICF cookie '#cookie' is rejected rather than restarting from the first page (which would duplicate results)"() {
        when:
        PageCursor.decode(cookie)

        then:
        thrown(IllegalArgumentException)

        where:
        cookie << ['garbage!', 'bm90LWpzb24', 'e30']   // not base64; base64 of non-JSON; base64 of {} (no cookie)
    }

    def "toQuery sends pageSize, and pagedResultsCookie only after the first page"() {
        expect:
        new PageCursor().toQuery(50) == [pageSize: 50]
        new PageCursor(serviceCookie: '100').toQuery(50) == [pageSize: 50, pagedResultsCookie: '100']
    }

    def "next follows the cookie the response returned"() {
        expect:
        PageCursor.next(page([results: [[uid: 'a']], pagedResultsCookie: '1', totalResults: 72, hasMore: true])).serviceCookie == '1'
    }

    @Unroll
    def "next is null when there are no more pages: #why"() {
        expect:
        PageCursor.next(page(body)) == null

        where:
        why                                    | body
        'the cookie is null'                   | [results: [[uid: 'a']], pagedResultsCookie: null, totalResults: 1, hasMore: false]
        'the cookie is absent'                 | [results: [[uid: 'a']]]
        'an empty page, even with a cookie'    | [results: [], pagedResultsCookie: '5', hasMore: true]
    }

    @Unroll
    def "totalResults is #expected for #body"() {
        expect:
        PageCursor.totalResults(page(body)) == expected

        where:
        body                                  | expected
        [results: [], totalResults: 72]       | 72
        [results: [], totalResults: -1]       | -1
        [results: []]                         | -1
    }
}
