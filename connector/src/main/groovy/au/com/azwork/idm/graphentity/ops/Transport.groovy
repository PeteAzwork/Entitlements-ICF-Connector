package au.com.azwork.idm.graphentity.ops

import au.com.azwork.idm.graphentity.http.HttpResponse
import org.identityconnectors.framework.common.exceptions.ConnectorIOException

/** Runs one call to the service, turning a transport failure into the retryable ConnectorIOException (§13). */
class Transport {
    static HttpResponse call(Closure<HttpResponse> request) {
        try {
            request.call()
        } catch (IOException e) {
            throw new ConnectorIOException("The service could not be reached: ${e.class.simpleName}${e.message ? ': ' + e.message : ''}", e)
        }
    }
}
