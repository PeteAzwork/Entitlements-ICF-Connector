package au.com.azwork.idm.graphentity.support

import org.identityconnectors.common.logging.Log
import org.identityconnectors.common.logging.LogSpi

/**
 * An ICF {@link Log} whose output is captured in memory so specs can assert on WARN/ERROR lines
 * (e.g. the dropped-edge warnings of §9.1 and the no-PII rule of §13).
 */
class CapturingLog implements LogSpi {
    final List<Map> entries = []

    @Override
    void log(Class<?> clazz, String method, Log.Level level, String message, Throwable ex) {
        entries << [level: level, message: message, ex: ex]
    }

    @Override
    void log(Class<?> clazz, StackTraceElement caller, Log.Level level, String message, Throwable ex) {
        entries << [level: level, message: message, ex: ex]
    }

    @Override
    boolean isLoggable(Class<?> clazz, Log.Level level) { true }

    @Override
    boolean needToInferCaller(Class<?> clazz, Log.Level level) { false }

    List<String> messagesAt(Log.Level level) { entries.findAll { it.level == level }*.message }

    String allText() { entries*.message.join('\n') }

    /**
     * Builds an ICF Log that writes into this capture. {@code Log.getLog(Class, LogSpi)} is package-private;
     * Groovy's dynamic dispatch reaches it, which is acceptable for test support code.
     */
    Log asLog() {
        Log.getLog(CapturingLog, this)
    }
}
