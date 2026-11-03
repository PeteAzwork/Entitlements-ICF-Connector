package au.com.azwork.idm.graphentity.http

import au.com.azwork.idm.graphentity.support.Fixtures
import org.identityconnectors.framework.common.exceptions.AlreadyExistsException
import org.identityconnectors.framework.common.exceptions.ConnectorException
import org.identityconnectors.framework.common.exceptions.ConnectorIOException
import org.identityconnectors.framework.common.exceptions.ConnectorSecurityException
import org.identityconnectors.framework.common.exceptions.InvalidAttributeValueException
import org.identityconnectors.framework.common.exceptions.InvalidCredentialException
import org.identityconnectors.framework.common.exceptions.UnknownUidException
import spock.lang.Specification
import spock.lang.Unroll

import static au.com.azwork.idm.graphentity.http.OpContext.*

/** Design §13 — HTTP outcome → ICF exception mapping table. */
class HttpErrorMapperSpec extends Specification {

    static HttpResponse resp(int status, Map headers = [:], String raw = null) {
        new HttpResponse(status: status, headers: headers, rawBody: raw)
    }

    @Unroll
    def "#status in #context returns normally (success)"() {
        expect:
        !HttpErrorMapper.raise(resp(status), context, Fixtures.connectorConfig())

        where:
        [status, context] << [[200, 201, 204], OpContext.values() as List].combinations()
    }

    @Unroll
    def "400 in #context → InvalidAttributeValueException"() {
        when:
        HttpErrorMapper.raise(resp(400, [:], '{"error":"bad"}'), context, Fixtures.connectorConfig())

        then:
        thrown(InvalidAttributeValueException)

        where:
        context << OpContext.values()
    }

    @Unroll
    def "#status in TEST → InvalidCredentialException"() {
        when:
        HttpErrorMapper.raise(resp(status), TEST, Fixtures.connectorConfig())

        then:
        thrown(InvalidCredentialException)

        where:
        status << [401, 403]
    }

    @Unroll
    def "#status in #context → ConnectorSecurityException"() {
        when:
        HttpErrorMapper.raise(resp(status), context, Fixtures.connectorConfig())

        then:
        thrown(ConnectorSecurityException)

        where:
        [status, context] << [[401, 403], [GET, SEARCH, CREATE, UPDATE, DELETE]].combinations()
    }

    @Unroll
    def "404 in #context is tolerated (returns true: absent)"() {
        expect:
        HttpErrorMapper.raise(resp(404), context, Fixtures.connectorConfig())

        where:
        context << [GET, SEARCH]
    }

    def "404 in UPDATE → UnknownUidException"() {
        when:
        HttpErrorMapper.raise(resp(404), UPDATE, Fixtures.connectorConfig())

        then:
        thrown(UnknownUidException)
    }

    def "404 in DELETE is tolerated when deleteMissingTolerated"() {
        expect:
        HttpErrorMapper.raise(resp(404), DELETE, Fixtures.connectorConfig(deleteMissingTolerated: true))
    }

    def "404 in DELETE → UnknownUidException when not tolerated"() {
        when:
        HttpErrorMapper.raise(resp(404), DELETE, Fixtures.connectorConfig(deleteMissingTolerated: false))

        then:
        thrown(UnknownUidException)
    }

    def "404 in CREATE is unexpected → ConnectorException"() {
        when:
        HttpErrorMapper.raise(resp(404), CREATE, Fixtures.connectorConfig())

        then:
        def e = thrown(ConnectorException)
        !(e instanceof UnknownUidException)
    }

    def "409 in CREATE → AlreadyExistsException"() {
        when:
        HttpErrorMapper.raise(resp(409), CREATE, Fixtures.connectorConfig())

        then:
        thrown(AlreadyExistsException)
    }

    @Unroll
    def "409 in #context → ConnectorException (not AlreadyExists)"() {
        when:
        HttpErrorMapper.raise(resp(409), context, Fixtures.connectorConfig())

        then:
        def e = thrown(ConnectorException)
        !(e instanceof AlreadyExistsException)

        where:
        context << [UPDATE, DELETE]
    }

    @Unroll
    def "#status in #context → ConnectorIOException (retryable)"() {
        when:
        HttpErrorMapper.raise(resp(status), context, Fixtures.connectorConfig())

        then:
        thrown(ConnectorIOException)

        where:
        [status, context] << [[408, 429, 500, 502, 503, 504], OpContext.values() as List].combinations()
    }

    def "429 message carries the Retry-After hint so operators can tune retry configuration"() {
        when:
        HttpErrorMapper.raise(resp(429, ['Retry-After': '30']), UPDATE, Fixtures.connectorConfig())

        then:
        def e = thrown(ConnectorIOException)
        e.message.contains('30')
    }

    @Unroll
    def "unmapped status #status → ConnectorException"() {
        when:
        HttpErrorMapper.raise(resp(status), GET, Fixtures.connectorConfig())

        then:
        def e = thrown(ConnectorException)
        !(e instanceof ConnectorIOException)

        where:
        status << [301, 302, 405, 410, 418, 422]
    }

    def "the exception message includes status and context but never the request credentials"() {
        when:
        HttpErrorMapper.raise(resp(400, ['Authorization': 'Basic c2VjcmV0'], '{"error":"x","authorization":"Basic c2VjcmV0"}'), CREATE, Fixtures.connectorConfig())

        then:
        def e = thrown(InvalidAttributeValueException)
        e.message.contains('400')
        e.message.contains('CREATE')
        !e.message.contains('c2VjcmV0')
    }

    // ---- Retry-After ----

    @Unroll
    def "retryAfterSeconds for header '#header' is #expected"() {
        expect:
        HttpErrorMapper.retryAfterSeconds(resp(429, header == null ? [:] : ['Retry-After': header])) == expected

        where:
        header  | expected
        '30'    | 30L
        '0'     | 0L
        null    | null
        ''      | null
        'soon'  | null
        '-5'    | null
    }

    def "retryAfterSeconds accepts an HTTP-date in the future and returns a non-negative delta"() {
        given:
        def future = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(2)
        def header = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(future)

        when:
        def secs = HttpErrorMapper.retryAfterSeconds(resp(429, ['Retry-After': header]))

        then:
        secs != null
        secs > 60 && secs <= 120
    }

    def "header name lookup is case-insensitive"() {
        expect:
        HttpErrorMapper.retryAfterSeconds(resp(429, ['retry-after': '7'])) == 7L
    }

    // ---- redaction ----

    @Unroll
    def "redact hides credential-like content: #input"() {
        expect:
        !HttpErrorMapper.redact(input).contains(secret)

        where:
        input                                                | secret
        'Authorization: Basic c3ZjOnNlY3JldA=='              | 'c3ZjOnNlY3JldA=='
        '{"password":"hunter2"}'                             | 'hunter2'
        '{"Password": "hunter2", "x": 1}'                    | 'hunter2'
        'authorization=Bearer abc.def.ghi'                   | 'abc.def.ghi'
    }

    def "redact leaves ordinary bodies intact and tolerates null"() {
        expect:
        HttpErrorMapper.redact('{"error":"name is required"}') == '{"error":"name is required"}'
        HttpErrorMapper.redact(null) == null
    }
}
