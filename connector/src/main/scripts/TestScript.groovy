/* TestScript.groovy — ICF Test operation (design §9.5). */
import au.com.azwork.idm.entitlementsaccelerator.ops.ScriptSupport
import au.com.azwork.idm.entitlementsaccelerator.ops.TestOperation

def ctx = ScriptSupport.contextFrom(configuration, ScriptSupport.connectionOf(binding), log)
new TestOperation(ctx).execute()
