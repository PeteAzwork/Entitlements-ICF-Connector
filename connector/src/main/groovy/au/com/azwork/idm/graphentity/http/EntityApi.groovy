package au.com.azwork.idm.graphentity.http

/**
 * The remote administrative API (design §2) as consumed by the operations. Implementations must not
 * throw on non-2xx statuses: they return the status so {@link HttpErrorMapper} can map it uniformly.
 * Transport failures (connection refused, timeouts) are raised as {@link java.io.IOException}.
 */
interface EntityApi {
    /** GET /admin/entities/{id} */
    HttpResponse get(String id)

    /** POST /admin/entities with a write-model document (EntityDTO). */
    HttpResponse create(Map<String, Object> document)

    /** PUT /admin/entities/{id} */
    HttpResponse replace(String id, Map<String, Object> document)

    /** DELETE /admin/entities/{id} */
    HttpResponse delete(String id)

    /**
     * POST /admin/entities/search with {@code fields} as the JSON body (decision P14). {@code [:]} sends
     * {@code {}}, the unfiltered search. Field names for the timestamp filter and paging are configuration
     * until WS0 confirms them.
     */
    HttpResponse search(Map<String, Object> fields)

    /** GET /admin/manifests — the archetype definitions, read by the Test operation (decision P22). */
    HttpResponse manifests()
}
