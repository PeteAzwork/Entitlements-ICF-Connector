/*
 * CustomizerScript.groovy — HTTP client construction for the Scripted REST connector (design §4, §14).
 *
 * The Scripted REST harness evaluates this script with a `customize` DSL. It is the single place that knows
 * about authentication (decision P13): the service's API key is read from the encrypted `password`
 * configuration property and sent raw in the `X-API-KEY` header (name overridable with `apiKeyHeader`
 * in customConfiguration). The provisioner sets `defaultAuthMethod` to NONE so IDM adds no other credentials.
 * The key never appears in operation scripts or logs.
 *
 * Harness contract (WS3.01, docs/service-contract.md §4): `init` receives the HttpClientBuilder with the
 * configuration as its delegate; the client it builds reaches the operation scripts as `connection`. `decorate`
 * receives that client; what it returns reaches them as `customizedConnection`. The work is in ClientCustomizer.
 */
import au.com.azwork.idm.entitlementsaccelerator.ops.ClientCustomizer
import org.apache.http.impl.client.HttpClientBuilder

def scriptBinding = binding

customize {
    init { HttpClientBuilder builder ->
        ClientCustomizer.init(builder, ClientCustomizer.configurationOf(delegate, scriptBinding))
    }
    decorate { httpClient ->
        ClientCustomizer.decorate(httpClient, ClientCustomizer.configurationOf(delegate, scriptBinding))
    }
    release { ignored = null ->
        // Nothing to release: the harness owns the client and its connection pool.
        null
    }
}
