package au.com.azwork.idm.entitlementsaccelerator.config

import org.identityconnectors.framework.common.exceptions.ConfigurationException

/** Edge direction from the perspective of the entity being written (design §2.2, §7). */
enum Direction {
    /** This entity is the relationship's source ({@code sourceID}, decision P17). */
    OUTBOUND,
    /** This entity is the relationship's target ({@code targetID}, decision P17). */
    INBOUND

    /** Parses the configuration string, trimmed and case-insensitive. Anything else is a ConfigurationException. */
    static Direction fromConfig(String value) {
        String v = value?.trim()?.toLowerCase()
        Direction d = values().find { it.name().toLowerCase() == v }
        if (d == null) throw new ConfigurationException("direction '${value}' is not valid: use 'outbound' or 'inbound'")
        d
    }

    Direction opposite() { this == OUTBOUND ? INBOUND : OUTBOUND }
}
