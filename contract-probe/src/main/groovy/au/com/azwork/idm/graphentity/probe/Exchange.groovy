package au.com.azwork.idm.graphentity.probe

import groovy.transform.Canonical

/**
 * One HTTP request and its outcome, as observed by the probe. Request headers are deliberately not kept,
 * so credentials can never reach a report or a recording.
 */
@Canonical
class Exchange {
    int n
    /** Which probe check issued it, e.g. "WS0.07". */
    String check
    String method
    /** Raw, already-encoded path, e.g. /admin/entities/a%2Fb */
    String path
    /** Query parameters as sent (unencoded). */
    Map<String, String> query = [:]
    /** 'valid', 'none' or 'invalid' — which credentials were presented. Never the credentials themselves. */
    String auth = 'valid'
    Object requestBody
    /** -1 when the request failed at transport level. */
    int status = -1
    /** Response headers, lower-cased name → first value. */
    Map<String, String> headers = [:]
    /** Parsed JSON body, or null. */
    Object body
    /** Raw body for non-JSON responses only, truncated. */
    String rawBody
    String error
    long durationMs

    boolean isSuccess() { status >= 200 && status < 300 }

    String getContentType() { headers['content-type'] }

    String ref() { "#${n}" }
}
