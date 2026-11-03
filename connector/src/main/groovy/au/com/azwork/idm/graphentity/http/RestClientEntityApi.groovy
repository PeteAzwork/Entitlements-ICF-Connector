package au.com.azwork.idm.graphentity.http

/**
 * {@link EntityApi} over the {@code groovyx.net.http.RESTClient} that the Scripted REST connector binds as
 * {@code connection}. The customizer script has already configured base URI, Basic auth, timeouts and TLS,
 * so this class only knows paths, content types and how to turn HTTP Builder's responses/exceptions into
 * {@link HttpResponse}s.
 */
class RestClientEntityApi implements EntityApi {

    static final String ENTITIES_PATH = '/admin/entities/'

    private final Object restClient

    RestClientEntityApi(Object restClient) {
        this.restClient = restClient
    }

    @Override
    HttpResponse get(String id) {
        throw new UnsupportedOperationException('TODO WS3: RestClientEntityApi.get')
    }

    @Override
    HttpResponse create(Map<String, Object> document) {
        throw new UnsupportedOperationException('TODO WS3: RestClientEntityApi.create')
    }

    @Override
    HttpResponse replace(String id, Map<String, Object> document) {
        throw new UnsupportedOperationException('TODO WS3: RestClientEntityApi.replace')
    }

    @Override
    HttpResponse delete(String id) {
        throw new UnsupportedOperationException('TODO WS3: RestClientEntityApi.delete')
    }

    @Override
    HttpResponse search(Map<String, Object> query) {
        throw new UnsupportedOperationException('TODO WS3: RestClientEntityApi.search')
    }

    /** URL-encodes a path segment so IDs containing '/', '?' or spaces address the right entity. TODO WS3. */
    static String encodeSegment(String id) {
        throw new UnsupportedOperationException('TODO WS3: RestClientEntityApi.encodeSegment')
    }
}
