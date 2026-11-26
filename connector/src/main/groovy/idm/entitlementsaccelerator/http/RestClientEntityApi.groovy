package idm.entitlementsaccelerator.http

import groovy.json.JsonException
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovyx.net.http.ContentType
import groovyx.net.http.Method

import java.nio.charset.StandardCharsets

/**
 * {@link EntityApi} over the {@code groovyx.net.http.RESTClient} that the Scripted REST connector binds as
 * {@code connection}. The customizer script has already configured base URI, the {@code X-API-KEY} header, timeouts and TLS,
 * so this class only knows paths, content types and how to turn HTTP Builder's responses/exceptions into
 * {@link HttpResponse}s.
 *
 * <p>Every request asks HTTP Builder for text and parses JSON here, so the raw body survives for diagnostics, and
 * installs the same handler for success and failure, so a non-2xx status is returned rather than thrown. The
 * request URI is built from the already-encoded path, because handing a path to {@code URIBuilder} would encode
 * it again ({@code %2F} becoming {@code %252F}).
 */
class RestClientEntityApi implements EntityApi {

    /** Base for per-entity paths: {@code /admin/entities/{id}}. */
    static final String ENTITIES_PATH = '/admin/entities/'
    /** Create is a POST to the collection, without a trailing slash, as the definition documents. */
    static final String CREATE_PATH = '/admin/entities'
    static final String MANIFESTS_PATH = '/admin/manifests'
    /** Search is a POST with a JSON body of filter and paging fields; {@code {}} means everything (P14). */
    static final String SEARCH_PATH = '/admin/entities/search'

    private final Object restClient

    RestClientEntityApi(Object restClient) {
        this.restClient = restClient
    }

    @Override
    HttpResponse get(String id) {
        send(Method.GET, ENTITIES_PATH + encodeSegment(id), null)
    }

    @Override
    HttpResponse create(Map<String, Object> document) {
        send(Method.POST, CREATE_PATH, document)
    }

    @Override
    HttpResponse replace(String id, Map<String, Object> document) {
        send(Method.PUT, ENTITIES_PATH + encodeSegment(id), document)
    }

    @Override
    HttpResponse delete(String id) {
        send(Method.DELETE, ENTITIES_PATH + encodeSegment(id), null)
    }

    @Override
    HttpResponse search(Map<String, Object> fields) {
        send(Method.POST, SEARCH_PATH, fields ?: [:])
    }

    @Override
    HttpResponse manifests() {
        send(Method.GET, MANIFESTS_PATH, null)
    }

    /** URL-encodes a path segment so IDs containing '/', '?' or spaces address the right entity. */
    static String encodeSegment(String id) {
        if (id == null) throw new IllegalArgumentException('An entity id is required')
        URLEncoder.encode(id, StandardCharsets.UTF_8.name()).replace('+', '%20')
    }

    private HttpResponse send(Method method, String encodedPath, Map<String, Object> body) throws IOException {
        URI target = new URI(baseUri() + encodedPath)
        Closure handler = { resp, data ->
            String raw = readText(data)
            Map<String, String> headers = new LinkedHashMap<>()
            resp.allHeaders?.each { h -> headers[h.name] = h.value }
            new HttpResponse(status: resp.status, body: parseJson(raw, headers), rawBody: raw, headers: headers)
        }
        restClient.request(target, method, ContentType.TEXT) {
            headers.Accept = 'application/json'
            if (body != null) {
                requestContentType = ContentType.JSON
                delegate.body = JsonOutput.toJson(body)
            }
            response.success = handler
            response.failure = handler
        } as HttpResponse
    }

    private String baseUri() {
        String base = restClient.uri?.toString()
        if (!base) throw new IllegalStateException('The REST client has no base URI; check serviceAddress')
        base.replaceAll('/+$', '')
    }

    private static String readText(Object data) {
        if (data == null) return null
        if (data instanceof Reader) return (data as Reader).text
        if (data instanceof InputStream) return (data as InputStream).getText(StandardCharsets.UTF_8.name())
        data.toString()
    }

    /** JSON when the content type says so, or when it is absent and the text looks like JSON; otherwise null. */
    private static Object parseJson(String raw, Map<String, String> headers) {
        if (!raw?.trim()) return null
        String type = headers.find { it.key.equalsIgnoreCase('Content-Type') }?.value?.toLowerCase()
        boolean json = type ? type.contains('json') : (raw.trim().startsWith('{') || raw.trim().startsWith('['))
        if (!json) return null
        try {
            new JsonSlurper().parseText(raw)
        } catch (JsonException | IllegalArgumentException e) {
            null
        }
    }
}
