package au.com.azwork.idm.graphentity.probe

import groovy.transform.Canonical

/** One answer to a WS0 question, with the exchanges that support it. */
@Canonical
class Finding {
    /** observed: seen directly. inferred: follows from what was seen. inconclusive: data did not allow an answer.
     *  not-tested: the check did not run (read-only mode, or a prerequisite was not found). error: the check failed. */
    static final List<String> CONFIDENCE = ['observed', 'inferred', 'inconclusive', 'not-tested', 'error']

    String task
    String key
    String question
    String answer
    String confidence
    String detail
    List<Integer> evidence = []
}
