/* DeleteScript.groovy — ICF Delete operation (design §9.3). */
import au.com.azwork.idm.graphentity.ops.DeleteOperation
import au.com.azwork.idm.graphentity.ops.ScriptSupport

def ctx = ScriptSupport.contextFrom(configuration, connection, log)
new DeleteOperation(ctx).execute(objectClass, uid, options)
