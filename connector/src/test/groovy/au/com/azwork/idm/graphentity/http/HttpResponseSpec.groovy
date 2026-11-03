package au.com.azwork.idm.graphentity.http

import spock.lang.Specification

class HttpResponseSpec extends Specification {

    def "isSuccess covers the 2xx range only"() {
        expect:
        new HttpResponse(status: 200).success
        new HttpResponse(status: 204).success
        !new HttpResponse(status: 199).success
        !new HttpResponse(status: 300).success
        !new HttpResponse(status: 404).success
    }

    def "header lookup is case-insensitive and null-safe"() {
        expect:
        new HttpResponse(status: 200, headers: ['Retry-After': '3']).header('retry-after') == '3'
        new HttpResponse(status: 200, headers: null).header('x') == null
        new HttpResponse(status: 200).header('x') == null
    }
}
