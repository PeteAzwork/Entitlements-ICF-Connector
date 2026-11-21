/*
 * SchemaScript.groovy — ICF Schema operation (design §5.2, §9.5).
 *
 * Harness contract (WS3.01): the script receives `builder`, the toolkit's ICFObjectBuilder, defines the schema
 * through `builder.schema { }` and returns nothing. SchemaDsl hands it the schema SchemaFactory builds from the
 * configuration. The Schema is also returned, which the harness ignores. No HTTP calls.
 */
import au.com.azwork.idm.graphentity.ops.SchemaOperation
import au.com.azwork.idm.graphentity.ops.ScriptSupport
import au.com.azwork.idm.graphentity.schema.SchemaDsl
import org.identityconnectors.framework.spi.Connector

def ctx = ScriptSupport.contextFrom(configuration, null, log)
def schema = new SchemaOperation(ctx).execute(ScriptSupport.optional(binding, 'connectorClass') ?: Connector)
def schemaBuilder = ScriptSupport.optional(binding, 'builder')
if (schemaBuilder != null) SchemaDsl.define(schemaBuilder, schema, log)
return schema
