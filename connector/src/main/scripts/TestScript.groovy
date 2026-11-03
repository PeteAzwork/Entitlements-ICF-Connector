/* TestScript.groovy — ICF Test operation (design §9.5). */
import au.com.azwork.idm.graphentity.ops.ScriptSupport
import au.com.azwork.idm.graphentity.ops.TestOperation

def ctx = ScriptSupport.contextFrom(configuration, connection, log)
new TestOperation(ctx).execute()
