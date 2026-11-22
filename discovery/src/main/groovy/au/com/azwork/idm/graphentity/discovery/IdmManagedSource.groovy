package au.com.azwork.idm.entitlementsaccelerator.discovery

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Reads {@code config/managed} from a running IDM over REST (WS6.08). Read-only: one GET of
 * {@code <idm-url>/openidm/config/managed}, nothing else. It authenticates with IDM's own header scheme
 * ({@code X-OpenIDM-Username}, {@code X-OpenIDM-Password}) using an IDM account of its own, separate from the
 * connector's service API key (§14). The password comes from an environment variable, never from an argument, and
 * never appears in a message.
 */
class IdmManagedSource {

    static final String PASSWORD_ENV = 'IDM_DISCOVERY_PASSWORD'
    static final String MANAGED_PATH = '/openidm/config/managed'

    static String fetch(String idmUrl, String user, String password, Duration timeout = Duration.ofSeconds(30)) {
        if (!idmUrl) throw new IllegalArgumentException('--idm-url is required')
        if (!user) throw new IllegalArgumentException('--idm-user is required with --idm-url')
        if (!password) throw new IllegalArgumentException("Set ${PASSWORD_ENV} to the IDM user's password; it is never taken from an argument")
        URI uri = URI.create(idmUrl.replaceAll('/+$', '') + MANAGED_PATH)
        HttpRequest request = HttpRequest.newBuilder(uri).GET().timeout(timeout)
            .header('Accept', 'application/json')
            .header('X-OpenIDM-Username', user)
            .header('X-OpenIDM-Password', password)
            .header('X-OpenIDM-NoSession', 'true')
            .build()
        HttpResponse<String> response
        try {
            response = HttpClient.newBuilder().connectTimeout(timeout).build().send(request, HttpResponse.BodyHandlers.ofString())
        } catch (IOException e) {
            throw new IOException("Could not read ${uri}: ${e.class.simpleName}${e.message ? ': ' + e.message : ''}".toString().replace(password, '***'))
        }
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new IOException("IDM refused ${user} reading ${uri} (HTTP ${response.statusCode()}): check the account and ${PASSWORD_ENV}")
        }
        if (response.statusCode() != 200) {
            throw new IOException("Reading ${uri} returned HTTP ${response.statusCode()}")
        }
        response.body()
    }
}
