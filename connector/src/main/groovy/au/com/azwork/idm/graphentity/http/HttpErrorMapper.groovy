package au.com.azwork.idm.entitlementsaccelerator.http

import au.com.azwork.idm.entitlementsaccelerator.config.ConnectorConfig
import groovy.json.JsonOutput
import org.identityconnectors.framework.common.exceptions.AlreadyExistsException
import org.identityconnectors.framework.common.exceptions.ConnectorException
import org.identityconnectors.framework.common.exceptions.ConnectorIOException
import org.identityconnectors.framework.common.exceptions.ConnectorSecurityException
import org.identityconnectors.framework.common.exceptions.InvalidAttributeValueException
import org.identityconnectors.framework.common.exceptions.InvalidCredentialException
import org.identityconnectors.framework.common.exceptions.UnknownUidException

import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * HTTP → ICF exception mapping (design §13).
 *
 * <pre>
 * 2xx                      → return normally
 * 400                      → InvalidAttributeValueException (body logged at ERROR)
 * 401/403                  → InvalidCredentialException (TEST) / ConnectorSecurityException (others)
 * 404 GET/SEARCH           → return normally (caller treats as empty)
 * 404 UPDATE               → UnknownUidException
 * 404 DELETE               → return normally when deleteMissingTolerated, else UnknownUidException
 * 404 CREATE               → ConnectorException (unexpected)
 * 409 CREATE               → AlreadyExistsException
 * 408/429/5xx              → ConnectorIOException (retryable); Retry-After honoured on 429
 * anything else            → ConnectorException
 * </pre>
 */
class HttpErrorMapper {

    /** Longest body excerpt carried in an exception message. */
    static final int MAX_BODY_IN_MESSAGE = 1000
    static final String MASK = '***'

    /** Names whose values are credentials: the API key header in its spellings, Authorization, password. */
    private static final String SECRET_NAMES = '(?:x-api-key|api[_-]?key|authorization|password)'
    /** {@code "name": "value"} in JSON. */
    private static final Pattern JSON_SECRET = Pattern.compile('(?i)("' + SECRET_NAMES + '"\\s*:\\s*)"(?:[^"\\\\]|\\\\.)*"')
    /** {@code name: value} (header) or {@code name=value} (query, form); an auth scheme before the value is kept. */
    private static final Pattern PLAIN_SECRET = Pattern.compile(
        '(?i)(' + SECRET_NAMES + '\\s*[:=]\\s*)((?:basic|bearer|digest|token)\\s+)?([^\\s&,;"\'}]+)')

    /**
     * Throws the mapped exception, or returns normally for statuses that the caller handles
     * (2xx, tolerated 404s). The returned boolean is {@code true} for a tolerated 404 so callers can
     * distinguish "found" from "absent".
     */
    static boolean raise(HttpResponse response, OpContext context, ConnectorConfig config) {
        int status = response.status
        if (status >= 200 && status < 300) return false
        String message = message(response, context, config)
        switch (status) {
            case 400:
                throw new InvalidAttributeValueException(message)
            case 401:
            case 403:
                if (context == OpContext.TEST) throw new InvalidCredentialException(message)
                throw new ConnectorSecurityException(message)
            case 404:
                if (context in [OpContext.GET, OpContext.SEARCH]) return true
                if (context == OpContext.DELETE && config?.deleteMissingTolerated) return true
                if (context in [OpContext.UPDATE, OpContext.DELETE]) throw new UnknownUidException(message)
                throw new ConnectorException("${message} (unexpected for ${context})")
            case 409:
                if (context == OpContext.CREATE) throw new AlreadyExistsException(message)
                throw new ConnectorException(message)
            case 408:
            case 429:
                Long wait = retryAfterSeconds(response)
                throw new ConnectorIOException(wait == null ? message : "${message} (Retry-After: ${wait} s)")
            default:
                if (status >= 500 && status < 600) throw new ConnectorIOException(message)
                throw new ConnectorException(message)
        }
    }

    /** Seconds to wait from a {@code Retry-After} header (delta-seconds or HTTP-date); null when absent/invalid. */
    static Long retryAfterSeconds(HttpResponse response) {
        String value = response?.header('Retry-After')?.trim()
        if (!value) return null
        if (value ==~ /\d+/) {
            try {
                return Long.parseLong(value)
            } catch (NumberFormatException e) {
                return null
            }
        }
        try {
            ZonedDateTime at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
            return Math.max(0L, Duration.between(ZonedDateTime.now(at.zone), at).seconds)
        } catch (DateTimeParseException e) {
            return null
        }
    }

    /**
     * Redacts anything that looks like a credential before a body is logged: {@code X-API-KEY} header or
     * field values (any case), {@code apiKey} fields, {@code Authorization} values and {@code password}
     * fields. {@link #raise} additionally removes the configured key's literal value wherever it appears,
     * because one shared key leaking exposes every consumer of the service.
     */
    static String redact(String body) {
        if (body == null) return null
        String out = JSON_SECRET.matcher(body).replaceAll('$1"' + MASK + '"')
        Matcher m = PLAIN_SECRET.matcher(out)
        StringBuffer sb = new StringBuffer()
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + (m.group(2) ?: '') + MASK))
        }
        m.appendTail(sb)
        sb.toString()
    }

    /** "HTTP 400 during CREATE: <redacted, key-stripped, truncated body>". */
    static String message(HttpResponse response, OpContext context, ConnectorConfig config) {
        String body = response.rawBody
        if (body == null && response.body != null) body = JsonOutput.toJson(response.body)
        String text = "HTTP ${response.status} during ${context}"
        if (body?.trim()) {
            String clean = stripKey(redact(body.trim()), config)
            if (clean.length() > MAX_BODY_IN_MESSAGE) clean = clean.substring(0, MAX_BODY_IN_MESSAGE) + '…'
            text += ": ${clean}"
        }
        text
    }

    /** Removes the configured key's literal value, however it is labelled (P13). */
    static String stripKey(String text, ConnectorConfig config) {
        if (text == null) return null
        char[] key = config?.apiKey
        if (key == null || key.length == 0) return text
        text.replace(String.valueOf(key), MASK)
    }
}
