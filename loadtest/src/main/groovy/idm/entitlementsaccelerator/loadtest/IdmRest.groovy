package idm.entitlementsaccelerator.loadtest

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/** IDM's REST API as an admin user, for the load test. The password is held as given and sent only in its header. */
class IdmRest {
    static class IdmException extends RuntimeException {
        final int status
        IdmException(String method, String path, int status, String body) {
            super("${method} ${path.replaceAll(/\?.*/, '')} → HTTP ${status}: ${body?.take(300)}".toString())
            this.status = status
        }
    }

    final String base
    private final String user
    private final String password
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    IdmRest(String base, String user, char[] password) {
        this.base = base
        this.user = user
        this.password = new String(password)
    }

    Map get(String path) { call('GET', path, null) as Map }
    Map put(String path, Map body) { call('PUT', path, body) as Map }
    Map create(String resource, Map body) { call('POST', "${resource}?_action=create", body) as Map }
    Map patch(String path, List operations) { call('POST', "${path}?_action=patch", operations) as Map }
    Map delete(String path) { call('DELETE', path, null) as Map }
    Map action(String path, String action, Object body = [:]) { call('POST', "${path}${path.contains('?') ? '&' : '?'}_action=${action}", body) as Map }

    /** Every result of a query, page by page; {@code fields} keeps the pages small. */
    List<Map> queryAll(String resource, String filter, String fields = '_id') {
        List<Map> all = []
        String cookie = null
        while (true) {
            String path = "${resource}?_queryFilter=${enc(filter)}&_fields=${enc(fields)}&_pageSize=1000" +
                (cookie ? "&_pagedResultsCookie=${enc(cookie)}" : '')
            Map page = get(path)
            all.addAll(page.result as List<Map>)
            cookie = page.pagedResultsCookie as String
            if (!cookie) return all
        }
    }

    /**
     * Runs a reconciliation to completion and returns IDM's record of it, plus {@code elapsedMs} from IDM's own start
     * and end times when it gives them (else this client's). Polled, since a large one outlasts a request timeout.
     */
    Map recon(String mapping, int timeoutSeconds) {
        long t0 = System.nanoTime()
        Map started = call('POST', "recon?_action=recon&mapping=${enc(mapping)}", [:]) as Map
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            Map r = get("recon/${started._id}")
            if (r.state in ['SUCCESS', 'FAILED', 'CANCELED']) {
                Long idmMs = null
                try { idmMs = Duration.between(Instant.parse(r.started as String), Instant.parse(r.ended as String)).toMillis() } catch (Exception ignored) { }
                r.elapsedMs = idmMs ?: (System.nanoTime() - t0).intdiv(1_000_000)
                return r
            }
            Thread.sleep(1000)
        }
        throw new IllegalStateException("Reconciliation ${started._id} of ${mapping} did not finish within ${timeoutSeconds}s")
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

    Object call(String method, String path, Object body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("${base}/${path}")).timeout(Duration.ofSeconds(120))
            .header('X-OpenIDM-Username', user).header('X-OpenIDM-Password', password)
            .header('X-OpenIDM-NoSession', 'true').header('Content-Type', 'application/json').header('Accept', 'application/json')
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JsonOutput.toJson(body)))
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
        if (r.statusCode() >= 400) throw new IdmException(method, path, r.statusCode(), r.body())
        r.body() ? new JsonSlurper().parseText(r.body()) : null
    }

    static String enc(String s) { URLEncoder.encode(s, 'UTF-8').replace('+', '%20') }
}
