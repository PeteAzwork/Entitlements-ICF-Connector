package au.com.azwork.idm.graphentity.integration

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** A small client for IDM's REST API, as the test administrator (the image's default openidm-admin). */
class IdmClient {
    final String base
    private final HttpClient http = HttpClient.newHttpClient()

    IdmClient(String base) { this.base = base }

    Map get(String path) { call('GET', path, null) as Map }
    Map put(String path, Map body) { call('PUT', path, body) as Map }
    Map create(String resource, Map body) { call('POST', "${resource}?_action=create", body) as Map }
    Map patch(String path, List operations) { call('POST', "${path}?_action=patch", operations) as Map }
    Map delete(String path) { call('DELETE', path, null) as Map }
    Map action(String path, String action, Object body = [:]) { call('POST', "${path}${path.contains('?') ? '&' : '?'}_action=${action}", body) as Map }
    List<Map> query(String resource, String filter = 'true') {
        (call('GET', "${resource}?_queryFilter=${URLEncoder.encode(filter, 'UTF-8')}", null) as Map).result as List<Map>
    }

    /** Runs a reconciliation to completion and returns its summary. */
    Map recon(String mapping) {
        Map started = call('POST', "recon?_action=recon&mapping=${mapping}&waitForCompletion=true", [:]) as Map
        get("recon/${started._id}")
    }

    /** Polls until {@code condition} holds (exceptions count as false), or fails naming {@code what}. */
    void awaitTrue(int seconds, String what, Closure<Boolean> condition) {
        long deadline = System.currentTimeMillis() + seconds * 1000L
        Throwable last = null
        while (System.currentTimeMillis() < deadline) {
            try {
                if (condition.call()) return
            } catch (Exception e) {
                last = e
            }
            Thread.sleep(500)
        }
        throw new IllegalStateException("Timed out after ${seconds}s waiting for ${what}${last ? ': ' + last.message : ''}")
    }

    /** The newest sync audit entries, newest first. */
    List<Map> syncAudit(int count = 20) {
        (call('GET', "audit/sync?_queryFilter=true&_sortKeys=-timestamp&_pageSize=${count}", null) as Map).result as List<Map>
    }

    void waitUntilReady(int seconds) {
        long deadline = System.currentTimeMillis() + seconds * 1000L
        String state = null
        while (System.currentTimeMillis() < deadline) {
            try {
                state = (call('GET', 'info/ping', null) as Map)?.state
                if (state == 'ACTIVE_READY') return
            } catch (IOException | IllegalStateException ignored) { }
            Thread.sleep(2000)
        }
        throw new IllegalStateException("IDM did not become ready within ${seconds}s (last state ${state})")
    }

    Object call(String method, String path, Object body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("${base}/${path}"))
            .header('X-OpenIDM-Username', 'openidm-admin').header('X-OpenIDM-Password', 'openidm-admin')
            .header('X-OpenIDM-NoSession', 'true').header('Content-Type', 'application/json').header('Accept', 'application/json')
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JsonOutput.toJson(body)))
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
        if (r.statusCode() >= 400) throw new IllegalStateException("${method} ${path} → HTTP ${r.statusCode()}: ${r.body()?.take(500)}")
        r.body() ? new JsonSlurper().parseText(r.body()) : null
    }
}
