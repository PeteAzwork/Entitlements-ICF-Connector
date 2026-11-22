package au.com.azwork.idm.entitlementsaccelerator.integration

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A forwarding HTTP proxy for plain http (absolute-URI requests), for the proxy spec: it records what it forwarded,
 * so a spec can show the connector's requests went through it. No CONNECT, so not for https.
 */
class ForwardProxy implements Closeable {
    private static final Set<String> HOP_BY_HOP = ['connection', 'keep-alive', 'proxy-connection', 'proxy-authorization',
        'proxy-authenticate', 'te', 'trailer', 'transfer-encoding', 'upgrade', 'host', 'content-length'] as Set
    final List<String> forwarded = new CopyOnWriteArrayList<>()
    /** The Proxy-Authorization header of each request, when the client sent one. */
    final List<String> proxyAuthorizations = new CopyOnWriteArrayList<>()
    private final HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.loopbackAddress, 0), 0)
    private final HttpClient client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build()

    ForwardProxy() {
        server.createContext('/') { HttpExchange ex -> forward(ex) }
        server.start()
    }

    String getAddress() { "http://127.0.0.1:${server.address.port}" }

    private void forward(HttpExchange ex) {
        try {
            URI target = ex.requestURI
            if (!target.absolute) { ex.sendResponseHeaders(400, -1); return }
            String auth = ex.requestHeaders.getFirst('Proxy-Authorization')
            if (auth) proxyAuthorizations << auth
            forwarded << "${ex.requestMethod} ${target}".toString()
            byte[] body = ex.requestBody.bytes
            HttpRequest.Builder req = HttpRequest.newBuilder(target)
                .method(ex.requestMethod, body.length ? HttpRequest.BodyPublishers.ofByteArray(body) : HttpRequest.BodyPublishers.noBody())
            ex.requestHeaders.each { String name, List<String> values ->
                if (!(name.toLowerCase() in HOP_BY_HOP)) values.each { req.header(name, it) }
            }
            HttpResponse<byte[]> resp = client.send(req.build(), HttpResponse.BodyHandlers.ofByteArray())
            resp.headers().map().each { String name, List<String> values ->
                if (!(name.toLowerCase() in HOP_BY_HOP) && !name.startsWith(':')) values.each { ex.responseHeaders.add(name, it) }
            }
            byte[] out = resp.body() ?: new byte[0]
            ex.sendResponseHeaders(resp.statusCode(), out.length ? out.length : -1)
            if (out.length) ex.responseBody.write(out)
        } catch (Exception e) {
            ex.sendResponseHeaders(502, -1)
        } finally {
            ex.close()
        }
    }

    @Override
    void close() { server.stop(0) }
}
