package au.com.azwork.idm.entitlementsaccelerator.support

import au.com.azwork.idm.entitlementsaccelerator.http.HttpResponse
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import groovy.json.JsonException
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.nio.charset.StandardCharsets
import javax.net.ssl.SSLContext

/**
 * Real HTTP front-end for {@link FakeGraphService}, so tools that speak HTTP (the contract probe, and later
 * WS8-style checks) can be tested against the service's semantics without a real instance.
 *
 * Routes, as in the service's OpenAPI definition:
 * <ul>
 *   <li>{@code POST /admin/entities} creates; any other method on the collection gets 405 with {@code Allow: POST};</li>
 *   <li>{@code POST /admin/entities/search} searches;</li>
 *   <li>{@code GET|PUT|DELETE /admin/entities/{id}};</li>
 *   <li>{@code POST /admin/entities/{id}/relationships} adds one relationship;</li>
 *   <li>{@code DELETE /admin/entities/{source}/relationships/{target}[/{type}]} removes relationships;</li>
 *   <li>{@code GET /admin/manifests}.</li>
 * </ul>
 * Adds what the in-memory model leaves to the transport: API-key authentication (a raw key in the
 * {@code X-API-KEY} header; a missing or wrong key gets 401 as problem details, as the service does), JSON
 * parsing (malformed JSON is a 400), and percent-decoding of each path segment.
 */
class FakeGraphHttpServer implements Closeable {

    static final String PREFIX = '/admin/entities'
    static final String MANIFESTS = '/admin/manifests'
    static final String DEFAULT_API_KEY = 'test-api-key'
    static final String DEFAULT_API_KEY_HEADER = 'X-API-KEY'

    final FakeGraphService service
    final String apiKey
    final String apiKeyHeader
    private final HttpServer server

    /** With {@code ssl}, serves https with that context's certificate (the deployment guide's TLS tests). */
    FakeGraphHttpServer(FakeGraphService service, String apiKey = DEFAULT_API_KEY, String apiKeyHeader = DEFAULT_API_KEY_HEADER,
                        InetSocketAddress bind = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), SSLContext ssl = null) {
        this.service = service
        this.apiKey = apiKey
        this.apiKeyHeader = apiKeyHeader
        if (ssl) {
            HttpsServer https = HttpsServer.create(bind, 0)
            https.httpsConfigurator = new HttpsConfigurator(ssl)
            this.server = https
        } else {
            this.server = HttpServer.create(bind, 0)
        }
        server.createContext(PREFIX) { HttpExchange ex -> handle(ex) }
        server.createContext(MANIFESTS) { HttpExchange ex -> handle(ex) }
    }

    FakeGraphHttpServer start() { server.start(); this }

    String getBaseUrl() { "${server instanceof HttpsServer ? 'https' : 'http'}://127.0.0.1:${server.address.port}" }

    int getPort() { server.address.port }

    @Override
    void close() { server.stop(0) }

    private void handle(HttpExchange ex) {
        try {
            if (!authorised(ex)) {
                send(ex, new HttpResponse(status: 401, body: [type: 'https://tools.ietf.org/html/rfc9110#section-15.5.2', title: 'Unauthorized', status: 401, traceId: '00-fake-00'],
                    headers: ['Content-Type': 'application/problem+json; charset=utf-8']))
                return
            }
            String rawPath = ex.requestURI.rawPath
            String method = ex.requestMethod
            if (rawPath == MANIFESTS || rawPath == MANIFESTS + '/') {
                send(ex, method == 'GET' ? service.manifests() : notAllowed('GET'))
                return
            }
            String rest = rawPath.length() > PREFIX.length() ? rawPath.substring(PREFIX.length()) : ''
            List<String> segs = rest.split('/', -1).drop(1).findAll { it != '' }.collect { decodeSegment(it) }

            if (segs.isEmpty()) {
                send(ex, method == 'POST' ? createFrom(ex) : notAllowed('POST'))
            } else if (segs == ['search']) {
                if (method != 'POST') { send(ex, notAllowed('POST')); return }
                def body = readJson(ex)
                send(ex, body instanceof Map ? service.search(body as Map) : badRequest('body must be a JSON object'))
            } else if (segs.size() == 1) {
                String id = segs[0]
                switch (method) {
                    case 'GET': send(ex, service.get(id)); break
                    case 'PUT':
                        def body = readJson(ex)
                        send(ex, body instanceof Map ? service.replace(id, body as Map) : badRequest('body must be a JSON object')); break
                    case 'DELETE': send(ex, service.delete(id)); break
                    default: send(ex, notAllowed('GET, PUT, DELETE'))
                }
            } else if (segs.size() == 2 && segs[1] == 'relationships' && method == 'POST') {
                def body = readJson(ex)
                send(ex, body instanceof Map ? service.addRelationship(segs[0], body as Map) : badRequest('body must be a JSON object'))
            } else if (segs.size() in [3, 4] && segs[1] == 'relationships' && method == 'DELETE') {
                send(ex, service.deleteRelationship(segs[0], segs[2], segs.size() == 4 ? segs[3] : null))
            } else {
                send(ex, new HttpResponse(status: 404))
            }
        } catch (MalformedJson e) {
            send(ex, badRequest('invalid JSON'))
        } catch (Exception e) {
            send(ex, new HttpResponse(status: 500))
        } finally {
            ex.close()
        }
    }

    private HttpResponse createFrom(HttpExchange ex) {
        def body = readJson(ex)
        body instanceof Map ? service.create(body as Map) : badRequest('body must be a JSON object')
    }

    private static HttpResponse notAllowed(String allow) { new HttpResponse(status: 405, headers: [Allow: allow]) }

    private static HttpResponse badRequest(String title) {
        new HttpResponse(status: 400, body: [type: 'https://tools.ietf.org/html/rfc9110#section-15.5.1', title: 'One or more validation errors occurred.', status: 400,
                                             errors: [entity: [title]], traceId: '00-fake-00'],
            headers: ['Content-Type': 'application/problem+json; charset=utf-8'])
    }

    /** The service compares a raw key; header names are case-insensitive in HTTP, and the JDK server honours that. */
    private boolean authorised(HttpExchange ex) {
        ex.requestHeaders.getFirst(apiKeyHeader) == apiKey
    }

    private static class MalformedJson extends RuntimeException {}

    private static Object readJson(HttpExchange ex) {
        String text = ex.requestBody.getText(StandardCharsets.UTF_8.name())
        try {
            return new JsonSlurper().parseText(text)
        } catch (JsonException | IllegalArgumentException e) {
            throw new MalformedJson()
        }
    }

    private static void send(HttpExchange ex, HttpResponse r) {
        String contentType = r.header('Content-Type')
        r.headers?.findAll { k, v -> !k.equalsIgnoreCase('Content-Type') }?.each { k, v -> ex.responseHeaders.add(k, v) }
        if (r.body == null) {
            ex.sendResponseHeaders(r.status, -1)
            return
        }
        byte[] bytes = JsonOutput.toJson(r.body).getBytes(StandardCharsets.UTF_8)
        ex.responseHeaders.set('Content-Type', contentType ?: 'application/json; charset=utf-8')
        ex.sendResponseHeaders(r.status, bytes.length)
        ex.responseBody.withStream { it.write(bytes) }
    }

    /** Percent-decodes one path segment. '+' is literal in a path, so protect it from URLDecoder. */
    static String decodeSegment(String raw) {
        URLDecoder.decode(raw.replace('+', '%2B'), StandardCharsets.UTF_8.name())
    }
}
