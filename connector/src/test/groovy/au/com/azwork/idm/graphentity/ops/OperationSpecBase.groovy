package au.com.azwork.idm.entitlementsaccelerator.ops

import au.com.azwork.idm.entitlementsaccelerator.support.CapturingLog
import au.com.azwork.idm.entitlementsaccelerator.support.FakeGraphService
import au.com.azwork.idm.entitlementsaccelerator.support.Fixtures
import spock.lang.Specification

/** Common wiring for operation specs: a fake service with the documented semantics and a capturing log. */
abstract class OperationSpecBase extends Specification {
    FakeGraphService service = new FakeGraphService()
    CapturingLog log = new CapturingLog()

    OperationContext ctx(Map configOverrides = [:]) {
        new OperationContext(Fixtures.connectorConfig(configOverrides), service, log.asLog())
    }
}
