/*
 * CustomizerScript.groovy — HTTP client construction for the Scripted REST connector (design §4, §14).
 *
 * The Scripted REST harness evaluates this script with a `customize` DSL. It is the single place that knows
 * about authentication: Basic auth credentials come from the (encrypted) connector configuration and are
 * injected as a default header; they never appear in operation scripts or logs.
 *
 * TODO WS3: implement `init` (credentials provider / default Authorization header, connect & socket
 * timeouts, TLS context for non-localhost) and `release` (close the client).
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
