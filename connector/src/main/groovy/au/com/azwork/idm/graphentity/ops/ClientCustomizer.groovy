package au.com.azwork.idm.graphentity.ops

import au.com.azwork.idm.graphentity.config.ConnectorConfig
import groovyx.net.http.RESTClient
import org.apache.http.HttpHost
import org.apache.http.HttpRequest
import org.apache.http.auth.AuthScope
import org.apache.http.auth.UsernamePasswordCredentials
import org.apache.http.HttpRequestInterceptor
import org.apache.http.client.HttpClient
import org.apache.http.client.config.RequestConfig
import org.apache.http.impl.client.BasicCredentialsProvider
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.http.protocol.HttpContext
import org.apache.http.ssl.SSLContexts
import org.identityconnectors.common.logging.Log
import org.identityconnectors.framework.common.exceptions.ConfigurationException

/**
 * The body of {@code CustomizerScript.groovy} (design §4, §14; WS3.13). The single place that knows about
 * authentication (P13): the API key from the encrypted {@code password} property is sent raw in {@code X-API-KEY}
 * (or the {@code apiKeyHeader} override). No Authorization header is ever added; a proxy's credentials, if configured, go to the proxy alone (Proxy-Authorization).
 *
 * <p>Harness contract (WS3.01): {@code init} receives the Apache {@code HttpClientBuilder} with the configuration
 * as its delegate; the client built from it is bound to the operation scripts as {@code connection}.
 * {@code decorate} receives that client and its result is bound as {@code customizedConnection}.
 */
class ClientCustomizer {
    static final int DEFAULT_CONNECT_TIMEOUT_MS = 10_000
    static final int DEFAULT_SOCKET_TIMEOUT_MS = 60_000
    private static final Log LOG = Log.getLog(ClientCustomizer)

    /**
     * The configuration object: the closure's delegate in the real harness (Ping's sample reads
     * {@code delegate as ScriptedRESTConfiguration}), else the script's {@code configuration} binding.
     */
    static Object configurationOf(Object delegate, Binding binding) {
        if (ScriptSupport.hasProperty(delegate, 'propertyBag')) return delegate
        if (binding?.hasVariable('configuration')) return binding.getVariable('configuration')
        throw new ConfigurationException('The customizer script received no connector configuration')
    }

    static void init(HttpClientBuilder builder, Object configuration) {
        Map bag = ScriptSupport.propertyBagOf(configuration)
        String header = bag.apiKeyHeader?.toString() ?: ConnectorConfig.DEFAULT_API_KEY_HEADER
        if (!(header ==~ /[A-Za-z0-9-]+/)) throw new ConfigurationException('apiKeyHeader must be an HTTP header name of letters, digits and dashes')
        String key = ScriptSupport.apiKeyFrom(configuration)
        if (!key) throw new ConfigurationException('apiKey is required: put the service API key in the password property (P13)')
        if (key.any { Character.isISOControl(it as char) }) throw new ConfigurationException('apiKey contains a control character, which cannot be sent in a header')

        // An interceptor rather than setDefaultHeaders, so headers the harness configured are kept.
        builder.addInterceptorFirst({ HttpRequest request, HttpContext context ->
            if (!request.containsHeader(header)) request.addHeader(header, key)
        } as HttpRequestInterceptor)

        int connect = millis(bag.connectTimeoutMs, DEFAULT_CONNECT_TIMEOUT_MS, 'connectTimeoutMs')
        int socket = millis(bag.socketTimeoutMs, DEFAULT_SOCKET_TIMEOUT_MS, 'socketTimeoutMs')
        builder.setDefaultRequestConfig(RequestConfig.custom()
            .setConnectTimeout(connect).setConnectionRequestTimeout(connect).setSocketTimeout(socket).build())
        builder.setSSLContext(SSLContexts.createSystemDefault())
        proxy(builder, configuration)

        URI address = serviceAddress(configuration)
        if (address?.scheme?.equalsIgnoreCase('http') && !(address.host in ['localhost', '127.0.0.1', '::1', '[::1]'])) {
            LOG.warn('{0}', "serviceAddress uses plain http to ${address.host}: the API key travels unencrypted; use https (§14)".toString())
        }
    }

    /**
     * Wraps the configured client in a RESTClient for the service. The handler for failures is the success
     * handler, so a non-2xx status is returned rather than thrown. If the harness hands over something other
     * than an HttpClient, the key header is set on the RESTClient instead, so authentication is never lost.
     */
    static RESTClient decorate(Object httpClient, Object configuration) {
        RESTClient client = new RESTClient(serviceAddress(configuration)?.toString())
        if (httpClient instanceof HttpClient) {
            client.client = httpClient as HttpClient
        } else {
            Map bag = ScriptSupport.propertyBagOf(configuration)
            client.headers[bag.apiKeyHeader?.toString() ?: ConnectorConfig.DEFAULT_API_KEY_HEADER] = ScriptSupport.apiKeyFrom(configuration)
        }
        client.handler.failure = client.handler.success
        client
    }

    /**
     * The Scripted REST {@code proxyAddress}, {@code proxyUsername} and {@code proxyPassword} (deployment guide §5.2).
     * The harness hands init a bare builder and applies none of them itself, so this does: the proxy carries every
     * request, and the credentials, if any, answer the proxy's 407 challenge only (they are scoped to the proxy's
     * host and port, so they never reach the service). The password is read from IDM's encrypted property and never
     * logged; an address carrying credentials is rejected without being repeated.
     */
    static void proxy(HttpClientBuilder builder, Object configuration) {
        Object raw = ScriptSupport.hasProperty(configuration, 'proxyAddress') ? configuration.proxyAddress : null
        if (raw == null || !raw.toString().trim()) return
        URI uri
        try {
            uri = raw instanceof URI ? raw as URI : new URI(raw.toString().trim())
        } catch (URISyntaxException ignored) {
            throw new ConfigurationException(raw.toString().contains('@') ? 'proxyAddress is not a valid URL' : "proxyAddress '${raw}' is not a valid URL".toString())
        }
        if (uri.rawUserInfo != null) {
            throw new ConfigurationException('proxyAddress must not contain credentials: use proxyUsername and proxyPassword')
        }
        if (!(uri.scheme?.toLowerCase() in ['http', 'https']) || !uri.host) {
            throw new ConfigurationException("proxyAddress '${uri}' must be an http or https URL with a host, such as http://proxy.example.com:3128".toString())
        }
        HttpHost host = new HttpHost(uri.host, uri.port, uri.scheme.toLowerCase())
        builder.setProxy(host)
        String user = ScriptSupport.hasProperty(configuration, 'proxyUsername') ? configuration.proxyUsername?.toString()?.trim() : null
        if (user) {
            BasicCredentialsProvider credentials = new BasicCredentialsProvider()
            credentials.setCredentials(new AuthScope(host), new UsernamePasswordCredentials(user, ScriptSupport.proxyPasswordFrom(configuration) ?: ''))
            builder.setDefaultCredentialsProvider(credentials)
        }
    }

    private static URI serviceAddress(Object configuration) {
        Object raw = ScriptSupport.hasProperty(configuration, 'serviceAddress') ? configuration.serviceAddress : null
        raw == null ? null : (raw instanceof URI ? raw as URI : new URI(raw.toString()))
    }

    private static int millis(Object raw, int dflt, String name) {
        if (raw == null || !raw.toString().trim()) return dflt
        String s = raw.toString().trim()
        if (!(s ==~ /\d+/) || (s as long) < 1 || (s as long) > Integer.MAX_VALUE) {
            throw new ConfigurationException("${name} must be a positive whole number of milliseconds, not '${s}'")
        }
        s as int
    }
}
