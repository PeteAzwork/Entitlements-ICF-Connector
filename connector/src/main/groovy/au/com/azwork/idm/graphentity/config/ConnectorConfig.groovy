package au.com.azwork.idm.graphentity.config

import au.com.azwork.idm.graphentity.convention.RelationshipConvention
import groovy.transform.Canonical
import org.identityconnectors.framework.common.exceptions.ConfigurationException

/**
 * Connector configuration as seen by the operation scripts (design §6).
 *
 * In a Scripted REST provisioner the standard properties ({@code serviceAddress}, {@code password}) are
 * first-class configuration properties, while the connector-specific ones ({@code objectTypes},
 * {@code pageSizeDefault}, {@code deleteMissingTolerated}, {@code apiKeyHeader}, {@code byIdLookup}) are
 * carried in {@code customConfiguration} and surface to scripts via
 * {@code configuration.propertyBag}. {@link #from(Map)} accepts a flat map so the loader is independent
 * of how the harness delivers them.
 *
 * <p>Authentication (decision P13): the service takes one constant key, sent raw in the {@code X-API-KEY}
 * header. The key is stored in the Scripted REST {@code password} property, which IDM encrypts, and
 * {@code ScriptSupport} hands it over as {@code apiKey}. There is no username. Missing and wrong keys both
 * get 401.
 *
 * <p>There is no timestamp-search setting: the service has no "updated since" search, and drift detection
 * is deferred to the final workstream (decision P16).
 */
@Canonical
class ConnectorConfig {
    static final int DEFAULT_PAGE_SIZE = 200
    static final int MAX_PAGE_SIZE = 1000
    static final String DEFAULT_API_KEY_HEADER = 'X-API-KEY'

    String serviceAddress
    /** The service's shared API key, sent raw. Held as char[] so it can be cleared; never logged. */
    char[] apiKey
    /** Header the key is sent in. A token of letters, digits and dashes. */
    String apiKeyHeader = DEFAULT_API_KEY_HEADER
    Map<String, ObjectTypeConfig> objectTypes = [:]
    int pageSizeDefault = DEFAULT_PAGE_SIZE
    boolean deleteMissingTolerated = true
    /** Optional aggressive mode of §9.1 (re-PUT after verification finds dropped edges). Off by default. */
    boolean rePutOnDroppedEdges = false
    /**
     * How a read by ID is done (decision P21): {@code get} uses {@code GET /admin/entities/{id}};
     * {@code search} uses a search with an {@code EQUALS} filter on the ID, the fallback while GET by ID fails (C1).
     */
    ByIdLookup byIdLookup = ByIdLookup.GET

    enum ByIdLookup { GET, SEARCH }

    /** Non-fatal findings from loading (e.g. a relationship whose counterpart type is not configured), to log at WARN. */
    List<String> warnings = []

    /**
     * Builds the configuration from a flat property map. {@code objectTypes} may be a JSON string or an
     * already-parsed Map. Required: {@code serviceAddress}, {@code apiKey}, {@code objectTypes}. Validates
     * them, that {@code apiKey} has no control characters (it becomes a header value), that
     * {@code apiKeyHeader} is a header name, that {@code byIdLookup} is {@code get} or {@code search}, the §7
     * relationship consistency rules and the P19 scope-archetype rule, throwing
     * {@link org.identityconnectors.framework.common.exceptions.ConfigurationException}. Keys it does not know are
     * ignored, because the harness delivers its own properties in the same map. Messages name the property, never
     * the key's value.
     */
    static ConnectorConfig from(Map<String, Object> props) {
        if (props == null) throw new ConfigurationException('The connector configuration is empty')
        String header = props.containsKey('apiKeyHeader') && props.apiKeyHeader != null ? props.apiKeyHeader.toString() : DEFAULT_API_KEY_HEADER
        if (!(header ==~ /[A-Za-z0-9-]+/)) {
            throw new ConfigurationException("apiKeyHeader must be an HTTP header name of letters, digits and dashes, such as ${DEFAULT_API_KEY_HEADER}")
        }
        Object rawTypes = props.objectTypes
        if (rawTypes == null) throw new ConfigurationException('objectTypes is required')
        Map<String, ObjectTypeConfig> types
        if (rawTypes instanceof Map) types = ObjectTypesParser.parse(rawTypes as Map<String, Object>)
        else if (rawTypes instanceof CharSequence) types = ObjectTypesParser.parse(rawTypes.toString())
        else throw new ConfigurationException('objectTypes must be a JSON object, as a string or a map')
        List<String> warnings = RelationshipConvention.validate(types)

        new ConnectorConfig(
            serviceAddress: serviceAddress(props.serviceAddress),
            apiKey: apiKey(props.apiKey),
            apiKeyHeader: header,
            objectTypes: types,
            pageSizeDefault: pageSize(props.pageSizeDefault),
            deleteMissingTolerated: bool(props, 'deleteMissingTolerated', true),
            rePutOnDroppedEdges: bool(props, 'rePutOnDroppedEdges', false),
            byIdLookup: byIdLookup(props.byIdLookup),
            warnings: warnings)
    }

    /** Looks up an object type by ICF object class name; null when unknown. */
    ObjectTypeConfig typeFor(String objectClassName) {
        objectClassName == null ? null : objectTypes?.get(objectClassName)
    }

    private static String serviceAddress(Object raw) {
        String text = raw?.toString()?.trim()
        if (!text) throw new ConfigurationException('serviceAddress is required: the base URL of the service, such as https://host:port')
        // An address with '@' may carry credentials, so it is never repeated in a message.
        String shown = text.contains('@') ? '(not shown)' : "'${text}'"
        URI uri
        try {
            uri = new URI(text)
        } catch (URISyntaxException e) {
            throw new ConfigurationException("serviceAddress ${shown} is not a valid URL")
        }
        if (!(uri.scheme?.toLowerCase() in ['http', 'https']) || !uri.host) {
            throw new ConfigurationException("serviceAddress ${shown} must be an http or https URL with a host, such as https://host:port")
        }
        if (uri.rawUserInfo != null) {
            throw new ConfigurationException('serviceAddress must not contain credentials: the API key goes in apiKey')
        }
        text.replaceAll('/+$', '')
    }

    private static char[] apiKey(Object raw) {
        char[] key = raw instanceof char[] ? Arrays.copyOf(raw as char[], (raw as char[]).length) : raw?.toString()?.toCharArray()
        if (key == null || key.length == 0 || String.valueOf(key).trim().isEmpty()) {
            throw new ConfigurationException('apiKey is required: the service authenticates by API key alone (P13)')
        }
        if (key.any { char c -> Character.isISOControl(c) }) {
            throw new ConfigurationException('apiKey contains a control character, which cannot be sent in a header; re-enter the key')
        }
        key
    }

    private static int pageSize(Object raw) {
        if (raw == null || (raw instanceof CharSequence && !raw.toString().trim())) return DEFAULT_PAGE_SIZE
        Integer n = raw instanceof Number && (raw as Number).doubleValue() == (raw as Number).intValue() ? (raw as Number).intValue()
            : raw instanceof CharSequence && raw.toString().trim() ==~ /-?\d+/ ? Integer.valueOf(raw.toString().trim()) : null
        if (n == null || n < 1 || n > MAX_PAGE_SIZE) {
            throw new ConfigurationException("pageSizeDefault must be a whole number from 1 to ${MAX_PAGE_SIZE}, not '${raw}'")
        }
        n
    }

    private static boolean bool(Map props, String key, boolean dflt) {
        Object raw = props[key]
        if (raw == null) return dflt
        if (raw instanceof Boolean) return raw as boolean
        String s = raw.toString().trim().toLowerCase()
        if (s == '') return dflt
        if (s == 'true') return true
        if (s == 'false') return false
        throw new ConfigurationException("${key} must be true or false, not '${raw}'")
    }

    private static ByIdLookup byIdLookup(Object raw) {
        String s = raw?.toString()?.trim()
        if (!s) return ByIdLookup.GET
        ByIdLookup v = ByIdLookup.values().find { it.name().equalsIgnoreCase(s) }
        if (v == null) throw new ConfigurationException("byIdLookup '${s}' is not valid: use 'get' or 'search'")
        v
    }

    @Override
    String toString() {
        // Never include the API key.
        "ConnectorConfig(serviceAddress=${serviceAddress}, apiKeyHeader=${apiKeyHeader}, objectTypes=${objectTypes?.keySet()})"
    }
}
