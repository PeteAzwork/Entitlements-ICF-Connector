package au.com.azwork.idm.entitlementsaccelerator.probe

import groovy.json.JsonException
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Minimal HTTP client for the probe (JDK {@code java.net.http}, no dependencies). Every call is recorded as
 * an {@link Exchange}. The API key is sent raw in its header on every request, as the connector will send it.
 * Request headers are never recorded, so the key cannot reach an output file.
 */
class ProbeHttp {

    static final int RAW_BODY_LIMIT = 2048

    final URI baseUri
    final List<Exchange> exchanges = []
    String currentCheck = '-'

    /** Sent when a check deliberately presents a wrong key. */
    static final String INVALID_KEY = 'ws0-probe-deliberately-invalid-key'

    final String apiKeyHeader
    private final String apiKey
    private final HttpClient client
    private final Duration timeout

    ProbeHttp(String baseUrl, String apiKeyHeader, char[] apiKey, Duration timeout) {
        this.baseUri = URI.create(baseUrl.replaceAll('/+$', ''))
        this.apiKeyHeader = apiKeyHeader
        this.apiKey = new String(apiKey)
        this.timeout = timeout
        this.client = HttpClient.newBuilder()
            .connectTimeout(timeout)
            .proxy(ProxySelector.getDefault())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    }

    /**
     * @param rawPath already percent-encoded path
     * @param opts {@code auth}: 'valid' (default), 'none' (no key header) or 'invalid' (a wrong key);
     *        {@code rawBody}: send this text verbatim
     */
    Exchange send(String method, String rawPath, Map<String, ?> query = [:], Object body = null, Map opts = [:]) {
        def ex = new Exchange(n: exchanges.size() + 1, check: currentCheck, method: method, path: rawPath,
            query: (query ?: [:]).collectEntries { k, v -> [(k as String): v as String] } as Map<String, String>,
            auth: opts.auth ?: 'valid', requestBody: opts.rawBody != null ? opts.rawBody : body)
        exchanges << ex

        String qs = ex.query ? '?' + ex.query.collect { k, v -> enc(k) + '=' + enc(v) }.join('&') : ''
        def builder = HttpRequest.newBuilder(URI.create(baseUri.toString() + rawPath + qs))
            .timeout(timeout)
            .header('Accept', 'application/json')
        if (ex.auth == 'valid') builder.header(apiKeyHeader, apiKey)
        if (ex.auth == 'invalid') builder.header(apiKeyHeader, INVALID_KEY)

        String payload = opts.rawBody != null ? opts.rawBody as String : (body != null ? JsonOutput.toJson(body) : null)
        def publisher = payload != null ? HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)
                                        : HttpRequest.BodyPublishers.noBody()
        if (payload != null) builder.header('Content-Type', 'application/json')
        builder.method(method, publisher)

        long t0 = System.nanoTime()
        try {
            HttpResponse<String> resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            ex.status = resp.statusCode()
            ex.headers = resp.headers().map().collectEntries { k, v -> [(k.toLowerCase()): v ? v[0] : null] } as Map<String, String>
            String text = resp.body()
            if (text) {
                try {
                    ex.body = new JsonSlurper().parseText(text)
                } catch (JsonException | IllegalArgumentException ignored) {
                    ex.rawBody = text.length() > RAW_BODY_LIMIT ? text.substring(0, RAW_BODY_LIMIT) + '…' : text
                }
            }
        } catch (IOException e) {
            ex.error = "${e.class.simpleName}: ${e.message}".toString()
        } finally {
            ex.durationMs = (System.nanoTime() - t0).intdiv(1_000_000L)
        }
        ex
    }

    Exchange get(String rawPath, Map<String, ?> query = [:], Map opts = [:]) { send('GET', rawPath, query, null, opts) }

    Exchange post(String rawPath, Object body, Map opts = [:]) { send('POST', rawPath, [:], body, opts) }

    Exchange put(String rawPath, Object body, Map opts = [:]) { send('PUT', rawPath, [:], body, opts) }

    Exchange delete(String rawPath) { send('DELETE', rawPath) }

    private static String enc(String s) { URLEncoder.encode(s ?: '', StandardCharsets.UTF_8) }

    /** Encodes an ID as one path segment (space → %20, '/' → %2F). */
    static String encodeSegment(String id) {
        URLEncoder.encode(id, StandardCharsets.UTF_8).replace('+', '%20')
    }
}
