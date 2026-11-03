package au.com.azwork.idm.graphentity.http

import groovy.transform.Canonical

/** Transport-agnostic HTTP outcome handed to the error mapper and operations. */
@Canonical
class HttpResponse {
    int status
    /** Parsed JSON body (Map/List) when the content type was JSON, otherwise null. */
    Object body
    /** Raw body text, when available, for diagnostic logging. */
    String rawBody
    /** Header names are matched case-insensitively by the helpers below. */
    Map<String, String> headers = [:]

    boolean isSuccess() { status >= 200 && status < 300 }

    String header(String name) {
        headers?.find { it.key?.equalsIgnoreCase(name) }?.value
    }
}
