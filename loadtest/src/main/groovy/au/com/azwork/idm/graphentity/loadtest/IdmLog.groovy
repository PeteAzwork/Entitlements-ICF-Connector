package au.com.azwork.idm.graphentity.loadtest

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * IDM's log file in a local Docker container ({@code --idm-container}), read with {@code docker exec}. IDM writes its
 * INFO lines (connector activations) to {@code logs/openidm0.log.0} only; the console carries WARNING and above.
 * Used to confirm a provisioner save was applied (H21) and to count what the connector logged during a phase.
 */
class IdmLog {
    static final String FILE = '/opt/openidm/logs/openidm0.log.0'
    private static final DateTimeFormatter TIME =
        DateTimeFormatter.ofPattern('MMM d, yyyy h:mm:ss.SSS a', Locale.ENGLISH).withZone(ZoneOffset.UTC)

    final String container

    IdmLog(String container) { this.container = container }

    /** Each log record since {@code since}: [instant, text of the record's lines after the header]. */
    List<List> records(Instant since, int tailLines = 200_000) {
        List<List> out = []
        Instant stamp = null
        StringBuilder text = null
        docker('exec', container, 'tail', '-n', tailLines.toString(), FILE).eachLine { String line ->
            def m = line =~ /^\[\d+\] (\w{3} \d{1,2}, \d{4} \d{1,2}:\d{2}:\d{2}\.\d{3} [AP]M) /
            if (m.find()) {
                if (stamp != null && stamp >= since) out << [stamp, text.toString()]
                stamp = Instant.from(TIME.parse(m.group(1)))
                text = new StringBuilder()
            } else if (text != null) {
                text.append(line).append('\n')
            }
        }
        if (stamp != null && stamp >= since) out << [stamp, text.toString()]
        out
    }

    /** The connector's lifecycle since {@code since}: 'updated', 'deactivated' or 'activated', in order. */
    List<List> provisionerEvents(Instant since, String name) {
        records(since, 5000).collect { List r ->
            String t = r[1] as String
            t.contains("component ${name} is activated") ? [r[0], 'activated']
                : t.contains("component ${name} is deactivated") ? [r[0], 'deactivated']
                : t =~ /Updating configuration file: .*provisioner\.openicf-${name}/ ? [r[0], 'updated'] : null
        }.findAll() as List<List>
    }

    /**
     * No provisioner event in the last 3 s, and the connector's last state change since {@code since}, if any, is an
     * activation. IDM logs "Updating configuration file" before or after the reactivation, so it is not a state.
     */
    boolean settled(Instant since, String name) {
        List<List> events = provisionerEvents(since, name)
        if (!events) return true
        List<List> states = events.findAll { it[1] != 'updated' }
        (events[-1][0] as Instant) < Instant.now().minusSeconds(3) && (!states || states[-1][1] == 'activated')
    }

    /** What the connector logged since {@code since}, by kind (runbook §6): counts only, never the lines themselves. */
    Map<String, Integer> connectorCounts(Instant since) {
        Map<String, Integer> counts = new TreeMap<>(['HTTP 429': 0, 'HTTP 5xx': 0, 'dropped edge': 0, 'SEVERE': 0, 'WARNING': 0])
        records(since).each { List r ->
            String t = r[1] as String
            if (t.contains('HTTP 429')) counts['HTTP 429']++
            if (t =~ /HTTP 5\d\d/) counts['HTTP 5xx']++
            if (t.contains('dropped edge')) counts['dropped edge']++
            if (t.startsWith('SEVERE')) counts.SEVERE++
            if (t.startsWith('WARNING')) counts.WARNING++
        }
        counts
    }

    static String docker(String... args) {
        Process p = new ProcessBuilder(['docker'] + (args as List)).redirectErrorStream(true).start()
        String out = p.inputStream.getText('UTF-8')
        if (p.waitFor() != 0) throw new IllegalStateException("docker ${args[0]} failed: ${out.take(300)}")
        out
    }
}
