/*
 * CustomizerScript.groovy — HTTP client construction for the Scripted REST connector (design §4, §14).
 *
 * The Scripted REST harness evaluates this script with a `customize` DSL. It is the single place that knows
 * about authentication (decision P13): the service's API key is read from the encrypted `password`
 * configuration property and sent raw as a default `X-API-KEY` header (name overridable with `apiKeyHeader`
 * in customConfiguration). The provisioner sets `defaultAuthMethod` to NONE so IDM adds no Basic header.
 * The key never appears in operation scripts or logs.
 *
 * TODO WS3: implement `init` (default API-key header, connect & socket timeouts, TLS context for
 * non-localhost), `decorate` (WS3.01) and `release`.
 */
import org.apache.http.impl.client.HttpClientBuilder

customize {
    init { HttpClientBuilder builder ->
        throw new UnsupportedOperationException('TODO WS3: CustomizerScript.init')
    }
    release { HttpClientBuilder builder ->
        // nothing to release yet
    }
}
