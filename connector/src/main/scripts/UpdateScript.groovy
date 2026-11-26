/* UpdateScript.groovy — ICF Update operation (design §8.4, §9.2). Returns the Uid. Value deltas are refused (WS3.10). */
import idm.entitlementsaccelerator.ops.ScriptSupport
import idm.entitlementsaccelerator.ops.UpdateOperation

ScriptSupport.requireFullUpdate(binding)
def ctx = ScriptSupport.contextFrom(configuration, ScriptSupport.connectionOf(binding), log)
return new UpdateOperation(ctx).execute(objectClass, uid, attributes, ScriptSupport.optional(binding, 'options'))
