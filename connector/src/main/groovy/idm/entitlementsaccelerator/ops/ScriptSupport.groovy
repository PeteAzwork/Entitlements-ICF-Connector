package idm.entitlementsaccelerator.ops

import idm.entitlementsaccelerator.config.ConnectorConfig
import idm.entitlementsaccelerator.http.RestClientEntityApi
import groovy.json.JsonOutput
import groovyx.net.http.RESTClient
import org.apache.http.client.HttpClient
import org.identityconnectors.common.logging.Log
import org.identityconnectors.common.security.GuardedString
import org.identityconnectors.framework.common.exceptions.ConfigurationException
import org.identityconnectors.framework.common.objects.Attribute
import org.identityconnectors.framework.common.objects.Name

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Bridge between the Scripted REST harness bindings and the library. Duck-typed on purpose: the
 * {@code configuration} binding is a ScriptedRESTConfiguration in IDM and a simple stand-in in tests.
 *
 * Expected properties on {@code configuration}: {@code serviceAddress} (URI or String), {@code password}
 * (GuardedString, String or char[]; it holds the service's API key, decision P13, and is returned as
 * {@code apiKey}), and {@code propertyBag} (Map populated from {@code customConfiguration}: objectTypes,
 * pageSizeDefault, deleteMissingTolerated, apiKeyHeader, byIdLookup). {@code username} is not used.
 *
 * <p>Harness contract (WS3.01, recorded in docs/service-contract.md §4): the operation scripts receive the
 * Apache {@code HttpClient} built by the customizer's {@code init} as {@code connection}, and whatever the
 * customizer's {@code decorate} returns as {@code customizedConnection}. {@link #connectionOf} prefers the
 * decorated client and {@link #contextFrom} wraps a bare {@code HttpClient} itself, so the API key header set by
 * {@code init} is used either way.
 */
class ScriptSupport {

    /** Parsed configurations, keyed by a digest of the flattened properties; recon runs the scripts constantly. */
    private static final int CACHE_SIZE = 8
    private static final Map<String, ConnectorConfig> CACHE = Collections.synchronizedMap(
        new LinkedHashMap<String, ConnectorConfig>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ConnectorConfig> eldest) { size() > CACHE_SIZE }
        })

    /**
     * The HTTP client binding a script should use: {@code customizedConnection} (the output of the customizer's
     * {@code decorate}) when the harness provides one, otherwise {@code connection}.
     */
    static Object connectionOf(Binding binding) {
        if (binding == null) return null
        if (binding.hasVariable('customizedConnection') && binding.getVariable('customizedConnection') != null) {
            return binding.getVariable('customizedConnection')
        }
        binding.hasVariable('connection') ? binding.getVariable('connection') : null
    }

    /**
     * Builds the operation context. {@code connection} may be a RESTClient (the decorated client), a bare Apache
     * HttpClient (wrapped here), or null for operations that make no HTTP calls (Schema). The configuration is
     * parsed and validated once per distinct property set; its warnings are logged at WARN when it is first built.
     */
    static OperationContext contextFrom(Object configuration, Object connection, Log log) {
        Map<String, Object> props = propertiesFrom(configuration)
        String key = digest(props)
        ConnectorConfig config = CACHE.get(key)
        if (config == null) {
            config = ConnectorConfig.from(props)
            config.warnings?.each { String w -> Logs.warn(log, w) }
            CACHE.put(key, config)
        }
        Object client = restClientFor(connection, config)
        new OperationContext(config, client == null ? null : new RestClientEntityApi(client), log)
    }

    /** Flattens the harness configuration into the map accepted by {@link ConnectorConfig#from}. */
    static Map<String, Object> propertiesFrom(Object configuration) {
        if (configuration == null) throw new ConfigurationException('The connector configuration is missing')
        Map<String, Object> props = new LinkedHashMap<>()
        Object custom = property(configuration, 'customConfiguration')
        Map bag = property(configuration, 'propertyBag') as Map ?: [:]
        if (bag.objectTypes == null && custom instanceof CharSequence && custom.toString().trim()) {
            props.putAll(parseCustomConfiguration(custom.toString()))
        }
        bag.each { k, v -> props[k as String] = v }
        props.remove('password')
        props.remove('username')
        props.serviceAddress = property(configuration, 'serviceAddress')?.toString()
        props.apiKey = secret(property(configuration, 'password'))
        props
    }

    /** True when the duck-typed {@code target} has the property; an Expando's dynamic properties count. */
    static boolean hasProperty(Object target, String name) {
        target != null && (target instanceof Expando ? (target as Expando).properties.containsKey(name) : target.hasProperty(name) != null)
    }

    private static Object property(Object target, String name) {
        hasProperty(target, name) ? target."$name" : null
    }

    /** customConfiguration is Groovy ConfigSlurper text, e.g. {@code objectTypes='{...}'}. */
    private static Map<String, Object> parseCustomConfiguration(String text) {
        try {
            new ConfigSlurper().parse(text).flatten().collectEntries { k, v -> [(k as String): v] } as Map<String, Object>
        } catch (Exception e) {
            throw new ConfigurationException("customConfiguration could not be parsed: ${e.class.simpleName}")
        }
    }

    /** The API key from the configuration's {@code password} property (P13); null when none is set. */
    static String apiKeyFrom(Object configuration) {
        configuration == null ? null : secret(property(configuration, 'password'))
    }

    /** The Scripted REST {@code proxyPassword}, which IDM encrypts like {@code password}; never logged. */
    static String proxyPasswordFrom(Object configuration) {
        configuration == null ? null : secret(property(configuration, 'proxyPassword'))
    }

    /** The connector-specific settings from customConfiguration, as the scripts see them. */
    static Map propertyBagOf(Object configuration) {
        configuration == null ? [:] : (property(configuration, 'propertyBag') as Map ?: [:])
    }

    /**
     * The create's attributes, with {@code __NAME__} restored from the harness's {@code id} binding when the
     * harness passed the name separately (Ping's create-script reference: {@code id} is the {@code __NAME__}).
     */
    static Set<Attribute> createAttributes(Binding binding) {
        Set<Attribute> attrs = new LinkedHashSet<>((binding.hasVariable('attributes') ? binding.getVariable('attributes') : null) as Set<Attribute> ?: [] as Set)
        Object id = binding.hasVariable('id') ? binding.getVariable('id') : null
        if (id != null && !attrs.any { it.name == Name.NAME }) attrs << new Name(id.toString())
        attrs
    }

    /**
     * WS3.10: the toolkit routes ADD_ATTRIBUTE_VALUES and REMOVE_ATTRIBUTE_VALUES through the update script. They
     * carry deltas, not the full state, and turning a delta into a PUT would delete every relationship it omits
     * (P15), so they are refused.
     */
    static void requireFullUpdate(Binding binding) {
        String operation = binding.hasVariable('operation') ? binding.getVariable('operation')?.toString() : null
        if (operation in ['ADD_ATTRIBUTE_VALUES', 'REMOVE_ATTRIBUTE_VALUES']) {
            throw new UnsupportedOperationException("${operation} is not supported: the service takes the full state on every update (P15), so updates must replace attributes, not add or remove values")
        }
    }

    /** Binding value or null, for bindings the harness provides only for some operations. */
    static Object optional(Binding binding, String name) {
        binding.hasVariable(name) ? binding.getVariable(name) : null
    }

    /** The API key held in {@code password} (P13), read without changing the configuration object. */
    private static String secret(Object password) {
        if (password == null) return null
        if (password instanceof GuardedString) {
            String value = null
            (password as GuardedString).access({ char[] chars -> value = new String(chars) } as GuardedString.Accessor)
            return value
        }
        if (password instanceof char[]) return new String(password as char[])
        password.toString()
    }

    private static Object restClientFor(Object connection, ConnectorConfig config) {
        if (connection == null || connection instanceof RESTClient) return connection
        if (connection instanceof HttpClient) {
            RESTClient client = new RESTClient(config.serviceAddress)
            client.client = connection as HttpClient
            return client
        }
        connection   // duck-typed stand-in (tests)
    }

    /** SHA-256 of the canonical property set, so the cache never holds the key in readable form. */
    private static String digest(Map<String, Object> props) {
        Map<String, Object> sorted = new TreeMap<>()
        props.each { k, v -> sorted[k] = v instanceof Map || v instanceof Collection ? JsonOutput.toJson(v) : v?.toString() }
        MessageDigest.getInstance('SHA-256').digest(JsonOutput.toJson(sorted).getBytes(StandardCharsets.UTF_8)).encodeHex().toString()
    }
}
