package au.com.azwork.idm.graphentity.http

import au.com.azwork.idm.graphentity.config.ConnectorConfig

/**
 * HTTP → ICF exception mapping (design §13).
 *
 * <pre>
 * 2xx                      → return normally
 * 400                      → InvalidAttributeValueException (body logged at ERROR)
 * 401/403                  → InvalidCredentialException (TEST) / ConnectorSecurityException (others)
 * 404 GET/SEARCH           → return normally (caller treats as empty)
 * 404 UPDATE               → UnknownUidException
 * 404 DELETE               → return normally when deleteMissingTolerated, else UnknownUidException
 * 404 CREATE               → ConnectorException (unexpected)
 * 409 CREATE               → AlreadyExistsException
 * 408/429/5xx              → ConnectorIOException (retryable); Retry-After honoured on 429
 * anything else            → ConnectorException
 * </pre>
 */
class HttpErrorMapper {

    /**
     * Throws the mapped exception, or returns normally for statuses that the caller handles
     * (2xx, tolerated 404s). The returned boolean is {@code true} for a tolerated 404 so callers can
     * distinguish "found" from "absent". TODO WS3.
     */
    static boolean raise(HttpResponse response, OpContext context, ConnectorConfig config) {
        throw new UnsupportedOperationException('TODO WS3: HttpErrorMapper.raise')
    }

    /** Seconds to wait from a {@code Retry-After} header (delta-seconds or HTTP-date); null when absent/invalid. TODO WS3. */
    static Long retryAfterSeconds(HttpResponse response) {
        throw new UnsupportedOperationException('TODO WS3: HttpErrorMapper.retryAfterSeconds')
    }

    /** Redacts anything that looks like a credential before a body is logged. TODO WS3. */
    static String redact(String body) {
        throw new UnsupportedOperationException('TODO WS3: HttpErrorMapper.redact')
    }
}
