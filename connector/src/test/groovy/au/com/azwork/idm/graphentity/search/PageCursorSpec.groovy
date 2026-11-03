package au.com.azwork.idm.graphentity.search

import au.com.azwork.idm.graphentity.http.HttpResponse
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Design §9.4 / §15 — paging through the ICF paged-results cookie. The remote protocol is unconfirmed; these
 * tests pin the assumed offset/pageSize + totalCount protocol. When the real protocol is confirmed, only
 * PageCursor and this spec should change.
 */
class PageCursorSpec extends Specification {

    def "a null or blank cookie is the first page"() {
        expect:
        PageCursor.decode(null).offset == 0
        PageCursor.decode('').offset == 0
        PageCursor.decode('  ').offset == 0
    }

    def "encode/decode round-trips the offset"() {
        expect:
        PageCursor.decode(new PageCursor(offset: 600).encode()).offset == 600
    }

    def "encode/decode round-trips an opaque server cursor when the service supplies one"() {
        expect:
        PageCursor.decode(new PageCursor(offset: 0, opaque: 'abc==').encode()).opaque == 'abc=='
    }

    @Unroll
    def "a corrupt cookie '#cookie' is rejected rather than restarting from the first page (which would duplicate results)"() {
        when:
        PageCursor.decode(cookie)

        then:
        thrown(IllegalArgumentException)

        where:
        cookie << ['garbage', 'offset=-1', '{"offset":"x"}']
    }

    def "toQuery emits offset and pageSize"() {
        expect:
        new PageCursor(offset: 200).toQuery(100) == [offset: 200, pageSize: 100]
    }

    def "toQuery prefers an opaque server cursor when present"() {
        expect:
        new PageCursor(opaque: 'tok').toQuery(100) == [cursor: 'tok', pageSize: 100]
    }

    def "next advances by the page size while totalCount says more remain"() {
        when:
        def next = new PageCursor(offset: 0).next(new HttpResponse(status: 200, body: [items: [1] * 100, totalCount: 250]), 100, 100)

        then:
        next.offset == 100
    }

    def "next is null on the last page by totalCount"() {
        expect:
        new PageCursor(offset: 200).next(new HttpResponse(status: 200, body: [items: [1] * 50, totalCount: 250]), 50, 100) == null
    }

    def "next is null when a page comes back short and there is no totalCount"() {
        expect:
        new PageCursor(offset: 0).next(new HttpResponse(status: 200, body: [items: [1] * 30]), 30, 100) == null
    }

    def "next continues when a page is full and there is no totalCount (we cannot know it was the last)"() {
        expect:
        new PageCursor(offset: 0).next(new HttpResponse(status: 200, body: [items: [1] * 100]), 100, 100).offset == 100
    }

    def "next picks up a server-supplied nextCursor"() {
        when:
        def next = new PageCursor().next(new HttpResponse(status: 200, body: [items: [1], nextCursor: 'tok2']), 1, 100)

        then:
        next.opaque == 'tok2'
    }

    def "an empty page is always the end"() {
        expect:
        new PageCursor(offset: 0).next(new HttpResponse(status: 200, body: [items: [], totalCount: 999]), 0, 100) == null
    }
}
