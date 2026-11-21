package au.com.azwork.idm.graphentity.ops

import org.identityconnectors.common.logging.Log

/**
 * Logs a finished message. ICF's {@link Log} treats its first argument as a {@code MessageFormat} pattern, so a
 * message containing braces (a JSON body) or apostrophes (quoted IDs) must be passed as an argument, never as the
 * pattern.
 */
class Logs {
    static void ok(Log log, Object message) { log?.ok('{0}', message?.toString()) }
    static void info(Log log, Object message) { log?.info('{0}', message?.toString()) }
    static void warn(Log log, Object message) { log?.warn('{0}', message?.toString()) }
    static void error(Log log, Object message) { log?.error('{0}', message?.toString()) }
}
