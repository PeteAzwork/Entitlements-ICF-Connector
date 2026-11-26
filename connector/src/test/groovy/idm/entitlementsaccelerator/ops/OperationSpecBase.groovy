package idm.entitlementsaccelerator.ops

import idm.entitlementsaccelerator.support.CapturingLog
import idm.entitlementsaccelerator.support.FakeAcceleratorService
import idm.entitlementsaccelerator.support.Fixtures
import spock.lang.Specification

/** Common wiring for operation specs: a fake service with the documented semantics and a capturing log. */
abstract class OperationSpecBase extends Specification {
    FakeAcceleratorService service = new FakeAcceleratorService()
    CapturingLog log = new CapturingLog()

    OperationContext ctx(Map configOverrides = [:]) {
        new OperationContext(Fixtures.connectorConfig(configOverrides), service, log.asLog())
    }
}
