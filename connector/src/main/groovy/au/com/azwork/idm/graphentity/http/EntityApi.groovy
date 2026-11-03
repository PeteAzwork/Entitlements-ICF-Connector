package au.com.azwork.idm.graphentity.http

/**
 * The remote administrative API (design §2) as consumed by the operations. Implementations must not
 * throw on non-2xx statuses: they return the status so {@link HttpErrorMapper} can map it uniformly.
 * Transport failures (connection refused, timeouts) are raised as {@link java.io.IOException}.
 */
interface EntityApi {
    /** GET /admin/entities/{id} */
    HttpResponse get(String id)

    /** POST /admin/entities/ */
    HttpResponse create(Map<String, Object> document)

    /** PUT /admin/entities/{id} */
    HttpResponse replace(String id, Map<String, Object> document)

    /** DELETE /admin/entities/{id} */
    HttpResponse delete(String id)

    /** GET /admin/entities?{query} — timestamp search with paging (parameter names are configuration). */
    HttpResponse search(Map<String, Object> query)
}
