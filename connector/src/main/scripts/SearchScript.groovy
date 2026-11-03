/* SearchScript.groovy — ICF Search/Get operation (design §9.4). Returns the SearchResult (paging cookie). */
import au.com.azwork.idm.graphentity.ops.ScriptSupport
import au.com.azwork.idm.graphentity.ops.SearchOperation

def ctx = ScriptSupport.contextFrom(configuration, connection, log)
return new SearchOperation(ctx).execute(objectClass, filter, handler, options)
