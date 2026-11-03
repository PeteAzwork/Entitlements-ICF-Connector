package au.com.azwork.idm.graphentity.convention

import au.com.azwork.idm.graphentity.config.Direction
import au.com.azwork.idm.graphentity.config.ObjectTypeConfig
import au.com.azwork.idm.graphentity.config.RelationshipConfig
import au.com.azwork.idm.graphentity.support.Fixtures
import org.identityconnectors.framework.common.exceptions.ConfigurationException
import spock.lang.Specification
import spock.lang.Unroll

/** Design §7 — relationship direction and type convention (normative). */
class RelationshipConventionSpec extends Specification {

    // ---- normalisation ----

    @Unroll
    def "normalise('#input') == '#expected'"() {
        expect:
        RelationshipConvention.normalise(input) == expected

        where:
        input          | expected
        'owns'         | 'Owns'
        'Owns'         | 'Owns'
        'ownsSet'      | 'Owns'
        'ownsList'     | 'Owns'
        'memberList'   | 'Member'
        'memberOfSet'  | 'MemberOf'
        'ownedBy'      | 'OwnedBy'
        'manager'      | 'Manager'
        'reports'      | 'Reports'
        'a'            | 'A'
        'set'          | 'Set'      // the suffix rule must not strip a whole name
        'list'         | 'List'
        'assetList'    | 'Asset'
        'settings'     | 'Settings' // 'Set' is not a suffix here
    }

    @Unroll
    def "normalise rejects blank input '#input'"() {
        when:
        RelationshipConvention.normalise(input)

        then:
        thrown(IllegalArgumentException)

        where:
        input << [null, '', '  ']
    }

    // ---- passive detection ----

    @Unroll
    def "isPassive('#name') == #passive"() {
        expect:
        RelationshipConvention.isPassive(name) == passive

        where:
        name          | passive
        'ownedBy'     | true
        'managedBy'   | true
        'ownedBySet'  | true
        'OwnedBy'     | true
        'owns'        | false
        'manager'     | false
        'reports'     | false
        'by'          | false   // a bare 'by' is not a passive form of anything
        'standby'     | false   // lower-case 'by' inside a word does not count
        'memberOf'    | false
    }

    // ---- pair resolution ----

    def "resolves the design's canonical pair: owns/ownedBy → Owns, owns outbound, ownedBy inbound"() {
        when:
        def r = RelationshipConvention.resolvePair('owns', 'ownedBy')

        then:
        r.resolved
        r.remoteType == 'Owns'
        r.outboundProperty == 'owns'
        r.inboundProperty == 'ownedBy'
    }

    def "pair resolution is symmetric in argument order"() {
        expect:
        RelationshipConvention.resolvePair('ownedBy', 'owns') == RelationshipConvention.resolvePair('owns', 'ownedBy')
    }

    @Unroll
    def "pair (#p, #q) resolves to type '#type' with outbound '#out'"() {
        when:
        def r = RelationshipConvention.resolvePair(p, q)

        then:
        r.resolved
        r.remoteType == type
        r.outboundProperty == out

        where:
        p            | q             | type      | out
        'manages'    | 'managedBy'   | 'Manages' | 'manages'
        'ownsSet'    | 'ownedBySet'  | 'Owns'    | 'ownsSet'
        'uses'       | 'usedBy'      | 'Uses'    | 'uses'
    }

    @Unroll
    def "pair (#p, #q) is unresolved because #why"() {
        when:
        def r = RelationshipConvention.resolvePair(p, q)

        then:
        !r.resolved
        r.remoteType == null
        r.reason

        where:
        p          | q           | why
        'manager'  | 'reports'   | 'neither side is passive (the §7 manager/reports example)'
        'ownedBy'  | 'managedBy' | 'both sides are passive'
        'owns'     | null        | 'there is no reverse property (violates A2)'
        'owns'     | ''          | 'the reverse property is blank'
        'owns'     | 'owns'      | 'a self-referential pair gives no direction'
    }

    // ---- single-property resolution with overrides ----

    def "an explicit override takes absolute precedence over the convention"() {
        given:
        def override = new RelationshipConfig(property: 'manager', remoteType: 'ReportsTo', direction: Direction.OUTBOUND, explicit: true)

        when:
        def r = RelationshipConvention.resolve('manager', 'reports', override)

        then:
        r.remoteType == 'ReportsTo'
        r.direction == Direction.OUTBOUND
    }

    def "an override can contradict what the convention would say"() {
        given:
        def override = new RelationshipConfig(property: 'owns', remoteType: 'Holds', direction: Direction.INBOUND, explicit: true)

        expect:
        with(RelationshipConvention.resolve('owns', 'ownedBy', override)) {
            remoteType == 'Holds'
            direction == Direction.INBOUND
        }
    }

    def "without an override the convention applies"() {
        expect:
        with(RelationshipConvention.resolve('ownedBy', 'owns', null)) {
            remoteType == 'Owns'
            direction == Direction.INBOUND
        }
        with(RelationshipConvention.resolve('owns', 'ownedBy', null)) {
            remoteType == 'Owns'
            direction == Direction.OUTBOUND
        }
    }

    def "a non-explicit entry (hints only) still uses the convention"() {
        given:
        def hints = new RelationshipConfig(property: 'owns', relatedType: 'account', reverseProperty: 'ownedBy', explicit: false)

        expect:
        RelationshipConvention.resolve('owns', null, hints).direction == Direction.OUTBOUND
    }

    def "sync:false yields null (excluded from sync) even when the convention would resolve"() {
        expect:
        RelationshipConvention.resolve('owns', 'ownedBy', new RelationshipConfig(property: 'owns', sync: false)) == null
    }

    def "an unresolvable property without override is a ConfigurationException naming the property"() {
        when:
        RelationshipConvention.resolve('manager', 'reports', null)

        then:
        def e = thrown(ConfigurationException)
        e.message.contains('manager')
        e.message.contains('reports')
    }

    // ---- whole-configuration validation (§7 consistency requirement) ----

    def "the design's example configuration validates with no warnings"() {
        expect:
        RelationshipConvention.validate([user: Fixtures.userType(), account: Fixtures.accountType()]) == []
    }

    def "both sides outbound is rejected"() {
        given:
        def account = Fixtures.accountType()
        account.relationships.ownedBy.direction = Direction.OUTBOUND

        when:
        RelationshipConvention.validate([user: Fixtures.userType(), account: account])

        then:
        def e = thrown(ConfigurationException)
        e.message.contains('ownedBy')
    }

    def "both sides inbound is rejected"() {
        given:
        def user = Fixtures.userType()
        user.relationships.owns.direction = Direction.INBOUND

        when:
        RelationshipConvention.validate([user: user, account: Fixtures.accountType()])

        then:
        thrown(ConfigurationException)
    }

    def "differing remoteType across a pair is rejected (no inventing OwnedBy on the inbound side)"() {
        given:
        def account = Fixtures.accountType()
        account.relationships.ownedBy.remoteType = 'OwnedBy'

        when:
        RelationshipConvention.validate([user: Fixtures.userType(), account: account])

        then:
        def e = thrown(ConfigurationException)
        e.message.contains('OwnedBy')
        e.message.contains('Owns')
    }

    def "two properties on one type colliding onto the same remoteType, direction and related type are rejected"() {
        given:
        def user = Fixtures.userType()
        user.relationships.holds = new RelationshipConfig(property: 'holds', remoteType: 'Owns', direction: Direction.OUTBOUND,
            relatedType: 'account', reverseProperty: 'heldBy', explicit: true)

        when:
        RelationshipConvention.validate([user: user, account: Fixtures.accountType()])

        then:
        def e = thrown(ConfigurationException)
        e.message.contains('holds')
        e.message.contains('owns')
    }

    def "the same remoteType towards different related types is not a collision"() {
        given:
        def user = Fixtures.userType()
        user.relationships.ownsDevice = new RelationshipConfig(property: 'ownsDevice', remoteType: 'Owns', direction: Direction.OUTBOUND,
            relatedType: 'device', reverseProperty: 'ownedBy', explicit: true)
        def device = new ObjectTypeConfig(name: 'device', idmResource: 'managed/device', entityKind: Fixtures.accountType().entityKind,
            idProperty: 'serial', relationships: [ownedBy: new RelationshipConfig(property: 'ownedBy', remoteType: 'Owns',
            direction: Direction.INBOUND, relatedType: 'user', reverseProperty: 'ownsDevice', explicit: true)])

        expect:
        RelationshipConvention.validate([user: user, account: Fixtures.accountType(), device: device]) == []
    }

    def "a counterpart type that is not configured is a warning, not an error"() {
        when:
        def warnings = RelationshipConvention.validate([user: Fixtures.userType()])

        then:
        warnings.size() == 1
        warnings[0].contains('account')
    }

    def "an entry with sync:false is ignored by validation"() {
        given:
        def user = Fixtures.userType()
        user.relationships.owns.sync = false
        def account = Fixtures.accountType()
        account.relationships.ownedBy.direction = Direction.OUTBOUND   // would be asymmetric if considered

        expect:
        RelationshipConvention.validate([user: user, account: account]) == []
    }

    def "convention-derived (non-explicit) entries are resolved before validation"() {
        given:
        def user = Fixtures.userType()
        user.relationships.owns = new RelationshipConfig(property: 'owns', relatedType: 'account', reverseProperty: 'ownedBy')
        def account = Fixtures.accountType()
        account.relationships.ownedBy = new RelationshipConfig(property: 'ownedBy', relatedType: 'user', reverseProperty: 'owns')

        expect:
        RelationshipConvention.validate([user: user, account: account]) == []
    }

    def "a non-explicit entry the convention cannot resolve fails validation with the manager/reports hint"() {
        given:
        def user = Fixtures.userType()
        user.relationships.manager = new RelationshipConfig(property: 'manager', relatedType: 'user', reverseProperty: 'reports')
        user.relationships.reports = new RelationshipConfig(property: 'reports', relatedType: 'user', reverseProperty: 'manager')

        when:
        RelationshipConvention.validate([user: user, account: Fixtures.accountType()])

        then:
        def e = thrown(ConfigurationException)
        e.message.contains('manager')
    }

    def "every violation is reported in a single exception rather than only the first"() {
        given:
        def user = Fixtures.userType()
        user.relationships.owns.direction = Direction.INBOUND
        user.relationships.holds = new RelationshipConfig(property: 'holds', remoteType: 'Holds', direction: Direction.OUTBOUND,
            relatedType: 'account', reverseProperty: 'heldBy', explicit: true)
        def account = Fixtures.accountType()
        account.relationships.heldBy = new RelationshipConfig(property: 'heldBy', remoteType: 'Held', direction: Direction.INBOUND,
            relatedType: 'user', reverseProperty: 'holds', explicit: true)

        when:
        RelationshipConvention.validate([user: user, account: account])

        then:
        def e = thrown(ConfigurationException)
        e.message.contains('owns')
        e.message.contains('holds')
    }
}
