package au.com.azwork.idm.entitlementsaccelerator.scenario

import au.com.azwork.idm.entitlementsaccelerator.ops.CreateOperation
import au.com.azwork.idm.entitlementsaccelerator.ops.DeleteOperation
import au.com.azwork.idm.entitlementsaccelerator.ops.OperationContext
import au.com.azwork.idm.entitlementsaccelerator.ops.SearchOperation
import au.com.azwork.idm.entitlementsaccelerator.ops.UpdateOperation
import au.com.azwork.idm.entitlementsaccelerator.support.CapturingLog
import au.com.azwork.idm.entitlementsaccelerator.support.FakeGraphService
import au.com.azwork.idm.entitlementsaccelerator.support.Fixtures
import org.identityconnectors.common.logging.Log
import org.identityconnectors.framework.common.objects.Uid
import org.identityconnectors.framework.common.objects.filter.FilterBuilder
import spock.lang.Specification
import spock.lang.Stepwise

/**
 * Appendix A — the worked example, end to end, against the in-memory service model.
 * Steps are ordered (Stepwise) because each builds on the previous remote state.
 */
@Stepwise
class WorkedExampleSpec extends Specification {

    static FakeGraphService service = new FakeGraphService()
    static CapturingLog log = new CapturingLog()
    static OperationContext ctx

    def setupSpec() { ctx = new OperationContext(Fixtures.connectorConfig(), service, log.asLog()) }

    static List<Map> relationshipsOf(String id) {
        service.get(id).body.relationships.collect { [source: it.source, target: it.target, type: it.type] }
    }

    static final Map OWNS = [source: 'Sanne', target: 'Current-Account-1-Sanne', type: 'Owns']

    def "1. initial recon runs the user mapping first: Sanne is created, the edge is dropped and logged"() {
        when:
        new CreateOperation(ctx).execute(Fixtures.USER, Fixtures.sanneAttributes(), null)

        then:
        relationshipsOf('Sanne') == []
        log.messagesAt(Log.Level.WARN) == ['dropped edge Sanne -Owns-> Current-Account-1-Sanne']
    }

    def "2. the account mapping creates the account: the same Owns edge, written from the target end, heals the drop"() {
        when:
        new CreateOperation(ctx).execute(Fixtures.ACCOUNT, Fixtures.accountAttributes(), null)

        then:
        relationshipsOf('Sanne') == [OWNS]
        relationshipsOf('Current-Account-1-Sanne') == [OWNS]
        log.messagesAt(Log.Level.WARN).size() == 1   // no new warning
    }

    def "3. Sanne's name changes: the update PUTs the full payload and the edge is preserved"() {
        when:
        new UpdateOperation(ctx).execute(Fixtures.USER, new Uid('Sanne'), Fixtures.sanneAttributes(name: 'Sanne Smith'), null)

        then:
        service.get('Sanne').body.Attributes.name.value == 'Sanne Smith'
        relationshipsOf('Sanne') == [OWNS]
    }

    def "4. a read of Sanne by ID shows the same edge string IDM computes (P17), and her archetype"() {
        given:
        def seen = []

        when:
        new SearchOperation(ctx).execute(Fixtures.USER, FilterBuilder.equalTo(new Uid('Sanne')), { seen << it; true }, null)

        then:
        seen[0].getAttributeByName('entityRelationships').value == [Fixtures.OWNS_JSON]
        seen[0].getAttributeByName('name').value == ['Sanne Smith']
    }

    def "5. the account is deleted in IDM: the entity and its edge go; Sanne's follow-up PUT restates no edges"() {
        when:
        new DeleteOperation(ctx).execute(Fixtures.ACCOUNT, new Uid('Current-Account-1-Sanne'), null)
        new UpdateOperation(ctx).execute(Fixtures.USER, new Uid('Sanne'), Fixtures.sanneAttributes(name: 'Sanne Smith', entityRelationships: []), null)

        then:
        service.get('Current-Account-1-Sanne').status == 404
        relationshipsOf('Sanne') == []
        service.edges.empty
    }

    def "6. a remote-side edge removal is corrected by the next full-state PUT (P15); detecting it is WS10"() {
        given:
        service.seed(Fixtures.accountWire())
        new UpdateOperation(ctx).execute(Fixtures.USER, new Uid('Sanne'), Fixtures.sanneAttributes(name: 'Sanne Smith'), null)
        service.edges.clear()   // manual intervention in the remote service

        when:
        new UpdateOperation(ctx).execute(Fixtures.USER, new Uid('Sanne'), Fixtures.sanneAttributes(name: 'Sanne Smith'), null)

        then:
        service.edges == [['Sanne', 'Current-Account-1-Sanne', 'Owns']] as Set
    }
}
