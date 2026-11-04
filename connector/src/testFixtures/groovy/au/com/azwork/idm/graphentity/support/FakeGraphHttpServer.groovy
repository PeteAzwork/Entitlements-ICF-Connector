package au.com.azwork.idm.graphentity.support

import au.com.azwork.idm.graphentity.http.HttpResponse
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import groovy.json.JsonException
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.nio.charset.StandardCharsets
import java.time.format.DateTimeParseException

/**
 * Real HTTP front-end for {@link FakeGraphService}, so tools that speak HTTP (the contract probe, and later
 * WS8-style checks) can be tested against the documented service semantics without a real instance.
 *
 * Routes (design §2): GET|POST {@code /admin/entities[/]}, GET|PUT|DELETE {@code /admin/entities/{id}}.
 * Adds what the in-memory model leaves to the transport: HTTP Basic auth (401 with a challenge), JSON
 * parsing, a 400 for a POST without {@code ID} or with a malformed body or timestamp, and percent-decoding
 * of the ID path segment.
 */
class FakeGraphHttpServer implements Closeable {

    static final String PREFIX = '/admin/entities'

    final FakeGraphService service
    final String username
    final String password
    private final HttpServer server

    FakeGraphHttpServer(FakeGraphService service, String username = 'svc-idm-sync', String password = 'secret') {
        this.service = service
        this.username = username
        this.password = password
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext(PREFIX) { HttpExchange ex -> handle(ex) }
    }

    FakeGraphHttpServer start() { server.start(); this }

    String getBaseUrl() { "http://127.0.0.1:${server.address.port}" }

    @Override
    void close() { server.stop(0) }

    private void handle(HttpExchange ex) {
        try {
            if (!authorised(ex)) {
                ex.responseHeaders.add('WWW-Authenticate', 'Basic realm="graph"')
                send(ex, new HttpResponse(status: 401, body: [error: 'unauthorised']))
                return
            }
            String rawPath = ex.requestURI.rawPath
            String rest = rawPath.length() > PREFIX.length() ? rawPath.substring(PREFIX.length()) : ''
            boolean collection = rest == '' || rest == '/'
            String id = collection ? null : decodeSegment(rest.substring(1))
            String method = ex.requestMethod

            if (collection && method == 'GET') {
                send(ex, service.search(parseQuery(ex.requestURI.rawQuery)))
            } else if (collection && method == 'POST') {
                def body = readJson(ex)
                if (!(body instanceof Map)) { send(ex, new HttpResponse(status: 400, body: [error: 'body must be a JSON object'])); return }
                if (!body.ID) { send(ex, new HttpResponse(status: 400, body: [error: 'ID is required'])); return }
                send(ex, service.create(body as Map))
            } else if (!collection && method == 'GET') {
                send(ex, service.get(id))
            } else if (!collection && method == 'PUT') {
                def body = readJson(ex)
                if (!(body instanceof Map)) { send(ex, new HttpResponse(status: 400, body: [error: 'body must be a JSON object'])); return }
                send(ex, service.replace(id, body as Map))
            } else if (!collection && method == 'DELETE') {
                send(ex, service.delete(id))
            } else {
                send(ex, new HttpResponse(status: 405, body: [error: "method ${method} not allowed".toString()]))
            }
        } catch (MalformedJson e) {
            send(ex, new HttpResponse(status: 400, body: [error: 'invalid JSON']))
        } catch (DateTimeParseException | NumberFormatException e) {
            send(ex, new HttpResponse(status: 400, body: [error: 'invalid query parameter']))
        } catch (Exception e) {
            send(ex, new HttpResponse(status: 500, body: [error: 'internal error']))
        } finally {
            ex.close()
        }
    }

    private boolean authorised(HttpExchange ex) {
        String header = ex.requestHeaders.getFirst('Authorization')
        header == 'Basic ' + "${username}:${password}".getBytes(StandardCharsets.UTF_8).encodeBase64().toString()
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
        r.headers?.each { k, v -> ex.responseHeaders.add(k, v) }
        if (r.body == null) {
            ex.sendResponseHeaders(r.status, -1)
            return
        }
        byte[] bytes = JsonOutput.toJson(r.body).getBytes(StandardCharsets.UTF_8)
        ex.responseHeaders.set('Content-Type', 'application/json')
        ex.sendResponseHeaders(r.status, bytes.length)
        ex.responseBody.withStream { it.write(bytes) }
    }

    /** Percent-decodes one path segment. '+' is literal in a path, so protect it from URLDecoder. */
    static String decodeSegment(String raw) {
        URLDecoder.decode(raw.replace('+', '%2B'), StandardCharsets.UTF_8.name())
    }

    static Map<String, Object> parseQuery(String rawQuery) {
        if (!rawQuery) return [:]
        rawQuery.split('&').findAll { it }.collectEntries { pair ->
            int i = pair.indexOf('=')
            String k = URLDecoder.decode(i < 0 ? pair : pair.substring(0, i), 'UTF-8')
            String v = i < 0 ? '' : URLDecoder.decode(pair.substring(i + 1), 'UTF-8')
            [(k): v]
        } as Map<String, Object>
    }
}
