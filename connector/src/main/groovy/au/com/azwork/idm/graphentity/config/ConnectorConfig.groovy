package au.com.azwork.idm.graphentity.config

import groovy.transform.Canonical

/**
 * Connector configuration as seen by the operation scripts (design §6).
 *
 * In a Scripted REST provisioner the standard properties ({@code serviceAddress}, {@code username},
 * {@code password}) are first-class configuration properties, while the connector-specific ones
 * ({@code objectTypes}, {@code timestampSearchParam}, {@code pageSizeDefault},
 * {@code deleteMissingTolerated}) are carried in {@code customConfiguration} and surface to scripts via
 * {@code configuration.propertyBag}. {@link #from(Map)} accepts a flat map so the loader is independent
 * of how the harness delivers them.
 */
@Canonical
class ConnectorConfig {
    static final String DEFAULT_TIMESTAMP_PARAM = 'updatedAfter'
    static final int DEFAULT_PAGE_SIZE = 200
    static final int MAX_PAGE_SIZE = 1000

    String serviceAddress
    String username
    /** Held as char[] so it can be cleared; never logged. */
    char[] password
    Map<String, ObjectTypeConfig> objectTypes = [:]
    String timestampSearchParam = DEFAULT_TIMESTAMP_PARAM
    int pageSizeDefault = DEFAULT_PAGE_SIZE
    boolean deleteMissingTolerated = true
    /** Optional aggressive mode of §9.1 (re-PUT after verification finds dropped edges). Off by default. */
    boolean rePutOnDroppedEdges = false

    /**
     * Builds the configuration from a flat property map. {@code objectTypes} may be a JSON string or an
     * already-parsed Map. Validates required fields and the §7 relationship consistency rules, throwing
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
        // Never include the password.
        "ConnectorConfig(serviceAddress=${serviceAddress}, username=${username}, objectTypes=${objectTypes?.keySet()})"
    }
}
