package au.com.azwork.idm.graphentity.mapping

import au.com.azwork.idm.graphentity.support.Fixtures
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.mozilla.javascript.Context
import org.mozilla.javascript.JavaScriptException
import org.mozilla.javascript.Scriptable
import spock.lang.Specification
import spock.lang.Unroll

/**
 * WS5.07 — the mapping library (mapping-lib/src/lib/graphEntityPayload.js) evaluated under Mozilla Rhino, the engine
 * IDM runs mapping scripts on, in ES5 mode (language version 1.8), with a fake {@code openidm} and {@code logger}
 * written in JavaScript. The Node suite (`npm test`) is the full specification; this smoke test proves the same
 * source parses and runs on Rhino, and that its §7 answers match the connector's (RelationshipConvention).
 * WS8.08 repeats it inside a real IDM, whose openidm.read returns wrapped Java collections.
 */
class GraphEntityPayloadRhinoSpec extends Specification {

    static final File LIBRARY = new File('../mapping-lib/src/lib/graphEntityPayload.js')

    /** The fake IDM: the Appendix A store and the provisioner configuration, as plain JavaScript objects. */
    static String prelude() {
        String customConfiguration = "objectTypes='${JsonOutput.toJson(Fixtures.objectTypesMap()).replace("'", "\\'")}'"
        """
        var logs = [];
        var logger = {
            debug: function (m) { logs.push(['debug', m]); }, info: function (m) { logs.push(['info', m]); },
            warn: function (m) { logs.push(['warn', m]); }, error: function (m) { logs.push(['error', m]); }
        };
        var reads = [];
        var store = {
            'managed/user/u-1': { _id: 'u-1', userName: 'Sanne', name: 'Sanne Groen', segments: ['VIP'],
                owns: [{ _ref: 'managed/account/a-1', _refResourceCollection: 'managed/account', _refResourceId: 'a-1' }] },
            'managed/account/a-1': { _id: 'a-1', accountId: 'Current-Account-1-Sanne',
                ownedBy: { _ref: 'managed/user/u-1' } }
        };
        var openidm = {
            read: function (id, params, fields) {
                reads.push(id);
                if (id === 'config/provisioner.openicf/graphentity') {
                    return { configurationProperties: { customConfiguration: ${JsonOutput.toJson(customConfiguration)} } };
                }
                if (id === 'config/managed') { return { objects: [] }; }
                return store[id] ? JSON.parse(JSON.stringify(store[id])) : null;
            }
        };
        var exports = {};
        """
    }

    /** Evaluates prelude + library + {@code expression} under Rhino in ES5 mode; returns the expression's JSON. */
    static Object run(String expression) {
        Context cx = Context.enter()
        try {
            cx.languageVersion = Context.VERSION_1_8
            cx.optimizationLevel = -1   // interpreted, as script engines commonly run small scripts
            Scriptable scope = cx.initStandardObjects()
            cx.evaluateString(scope, prelude(), 'prelude.js', 1, null)
            cx.evaluateString(scope, LIBRARY.getText('UTF-8'), 'graphEntityPayload.js', 1, null)
            Object result = cx.evaluateString(scope, "JSON.stringify(${expression})", 'smoke.js', 1, null)
            new JsonSlurper().parseText(Context.toString(result))
        } finally {
            Context.exit()
        }
    }

    def "the library parses and loads under Rhino in ES5 mode, exporting the documented functions"() {
        expect:
        run("Object.keys(exports).sort()") == ['CONFIG_PATH', 'buildArchetypes', 'buildRelationships', 'clearCache', 'isPassive',
                                               'loadTypeConfig', 'normalisePropertyName', 'onIdPropertyChange', 'resolvePair', 'resolveRelationship']
    }

    def "loadTypeConfig reads objectTypes out of the provisioner's customConfiguration"() {
        expect:
        run("exports.loadTypeConfig('account')").idProperty == 'accountId'
    }

    def "Appendix A: both ends emit the identical relationship string, by remote ID (P17)"() {
        when:
        def result = run("{ user: exports.buildRelationships('u-1', exports.loadTypeConfig('user')), " +
            "account: exports.buildRelationships('a-1', exports.loadTypeConfig('account')) }")

        then:
        result.user == [Fixtures.OWNS_JSON]
        result.account == [Fixtures.OWNS_JSON]
    }

    def "buildArchetypes puts the static archetypes first"() {
        expect:
        run("exports.buildArchetypes(store['managed/user/u-1'], (function () { var t = exports.loadTypeConfig('user'); " +
            "t.archetypeProperty = 'segments'; return t; })())") == ['Customer', 'VIP']
    }

    @Unroll
    def "under Rhino the §7 convention answers as the connector does: resolvePair('#p', '#q')"() {
        given:
        def js = run("exports.resolvePair('${p}', '${q}')")
        def groovy = au.com.azwork.idm.graphentity.convention.RelationshipConvention.resolvePair(p, q)

        expect:
        js.resolved == groovy.resolved
        js.remoteType == groovy.remoteType
        js.outboundProperty == groovy.outboundProperty

        where:
        [p, q] << [['owns', 'ownedBy'], ['ownedBySet', 'ownsSet'], ['manager', 'reports'], ['ownedBy', 'managedBy']]
    }

    def "errors surface as JavaScript exceptions naming the problem"() {
        when:
        run("exports.loadTypeConfig('group')")

        then:
        def e = thrown(JavaScriptException)
        e.message.contains('group')
    }
}
