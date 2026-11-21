package au.com.azwork.idm.graphentity.config

import groovy.transform.Canonical

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

    /**
     * Builds the configuration from a flat property map. {@code objectTypes} may be a JSON string or an
     * already-parsed Map. Required: {@code serviceAddress}, {@code apiKey}, {@code objectTypes}. Validates
     * them, that {@code apiKey} has no control characters (it becomes a header value), that
     * {@code apiKeyHeader} is a header name, that {@code byIdLookup} is {@code get} or {@code search}, the §7
     * relationship consistency rules and the P19 scope-archetype rule, throwing
     * {@link org.identityconnectors.framework.common.exceptions.ConfigurationException}.
     * TODO WS1.
     */
    static ConnectorConfig from(Map<String, Object> props) {
        throw new UnsupportedOperationException('TODO WS1: ConnectorConfig.from')
    }

    /** Looks up an object type by ICF object class name; null when unknown. TODO WS1. */
    ObjectTypeConfig typeFor(String objectClassName) {
        throw new UnsupportedOperationException('TODO WS1: ConnectorConfig.typeFor')
    }

    @Override
    String toString() {
        // Never include the API key.
        "ConnectorConfig(serviceAddress=${serviceAddress}, apiKeyHeader=${apiKeyHeader}, objectTypes=${objectTypes?.keySet()})"
    }
}
