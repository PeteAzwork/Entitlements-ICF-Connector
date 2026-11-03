package au.com.azwork.idm.graphentity.discovery

import spock.lang.Specification

/** Design §12 — regenerate-plus-review schema evolution workflow. */
class DiffSummarySpec extends Specification {

    static final Map CURRENT = [user: [idmResource: 'managed/user', entityKind: 'subject', idProperty: 'userName',
                                       attributes: ['name', 'mail'], archetypes: ['Customer'],
                                       relationships: [owns: [remoteType: 'Owns', direction: 'outbound']]]]

    def "identical documents produce no lines"() {
        expect:
        DiffSummary.summarise(CURRENT, CURRENT) == []
    }

    def "an added type is reported with '+'"() {
        given:
        def proposed = CURRENT + [account: [idmResource: 'managed/account']]

        expect:
        DiffSummary.summarise(CURRENT, proposed).any { it.startsWith('+') && it.contains('account') }
    }

    def "a removed type is reported with '-'"() {
        expect:
        DiffSummary.summarise(CURRENT, [:]).any { it.startsWith('-') && it.contains('user') }
    }

    def "an added attribute is reported under its type"() {
        given:
        def proposed = [user: CURRENT.user + [attributes: ['name', 'mail', 'telephoneNumber']]]

        expect:
        DiffSummary.summarise(CURRENT, proposed).any { it.startsWith('+') && it.contains('telephoneNumber') }
    }

    def "a changed scalar (idProperty) is reported with '~' showing old and new"() {
        given:
        def proposed = [user: CURRENT.user + [idProperty: 'mail']]

        expect:
        DiffSummary.summarise(CURRENT, proposed).any { it.startsWith('~') && it.contains('userName') && it.contains('mail') }
    }

    def "a changed relationship direction is reported"() {
        given:
        def proposed = [user: CURRENT.user + [relationships: [owns: [remoteType: 'Owns', direction: 'inbound']]]]

        expect:
        DiffSummary.summarise(CURRENT, proposed).any { it.startsWith('~') && it.contains('owns') }
    }

    def "null current means everything is added"() {
        expect:
        DiffSummary.summarise(null, CURRENT).every { it.startsWith('+') }
    }
}
