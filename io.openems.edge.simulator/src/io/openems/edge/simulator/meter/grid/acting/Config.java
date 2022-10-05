package io.openems.edge.simulator.meter.grid.acting;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

@ObjectClassDefinition(//
		name = "Simulator GridMeter Acting", //
		description = "This simulates an 'acting' Grid meter using data provided by a data source.")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "meter0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Datasource-ID", description = "ID of Simulator Datasource.")
	String datasource_id() default "datasource0";

	@AttributeDefinition(name = "Modbus-ID", description = "Id of the modbus bridge")
	String modbus_id();

	@AttributeDefinition(name = "Modbus Unit-ID", description = "The Unit-ID of the Modbus device")
	int modbusUnitId();

	@AttributeDefinition(name = "Modbus target filter", description = " This is auto-generated by 'Modbus-ID'")
	String Modbus_target() default "(enabled=true)";

	@AttributeDefinition(name = "Datasource target filter", description = "This is auto-generated by 'Datasource-ID'.")
	String datasource_target() default "(enabled=true)";

	String webconsole_configurationFactory_nameHint() default "Simulator GridMeter Acting [{id}]";
}