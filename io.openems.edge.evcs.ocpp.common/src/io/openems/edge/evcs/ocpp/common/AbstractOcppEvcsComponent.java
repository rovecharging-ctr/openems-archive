package io.openems.edge.evcs.ocpp.common;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.slf4j.Logger;

import eu.chargetime.ocpp.model.Request;
import io.openems.common.types.ChannelAddress;
import io.openems.common.types.OpenemsType;
import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.AbstractOpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.common.type.TypeUtils;
import io.openems.edge.evcs.api.Evcs;
import io.openems.edge.evcs.api.ManagedEvcs;
import io.openems.edge.evcs.api.MeasuringEvcs;
import io.openems.edge.evcs.api.Status;
import io.openems.edge.timedata.api.TimedataProvider;

public abstract class AbstractOcppEvcsComponent extends AbstractOpenemsComponent
		implements Evcs, MeasuringEvcs, EventHandler, TimedataProvider {

	private ChargingProperty lastChargingProperty = null;

	protected final Set<OcppProfileType> profileTypes;

	private final WriteHandler writeHandler = new WriteHandler(this);

	protected OcppServer ocppServer = null;

	protected UUID sessionId = null;

	private final ChargeSessionStamp sessionStart = new ChargeSessionStamp();

	private final ChargeSessionStamp sessionEnd = new ChargeSessionStamp();

	protected AbstractOcppEvcsComponent(OcppProfileType[] profileTypes,
			io.openems.edge.common.channel.ChannelId[] firstInitialChannelIds,
			io.openems.edge.common.channel.ChannelId[]... furtherInitialChannelIds) {
		super(firstInitialChannelIds, furtherInitialChannelIds);

		this.profileTypes = new HashSet<>(Arrays.asList(profileTypes));
	}

	public enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		;
		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}

	@Override
	protected void activate(ComponentContext context, String id, String alias, boolean enabled) {
		super.activate(context, id, alias, enabled);

		this.channel(Evcs.ChannelId.MAXIMUM_HARDWARE_POWER).setNextValue(this.getConfiguredMaximumHardwarePower());
		this.channel(Evcs.ChannelId.MINIMUM_HARDWARE_POWER).setNextValue(this.getConfiguredMinimumHardwarePower());
	}

	@Override
	public void handleEvent(Event event) {
		switch (event.getTopic()) {
		case EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE:
			this.setInitialTotalEnergyFromTimedata();
			break;

		case EdgeEventConstants.TOPIC_CYCLE_EXECUTE_WRITE:
			if (this.sessionId == null) {
				this.lostSession();
				return;
			}

			this.checkCurrentState();
			this.writeHandler.run();
			break;
		}
	}

	/**
	 * Initialize total energy value from from Timedata service if it is not already
	 * set.
	 */
	private void setInitialTotalEnergyFromTimedata() {
		var totalEnergy = this.getActiveConsumptionEnergy().orElse(null);

		// Total energy already set
		if (totalEnergy != null) {
			return;
		}

		var timedata = this.getTimedata();
		if (timedata == null) {
			return;
		}

		var componentId = this.id();
		timedata.getLatestValue(new ChannelAddress(componentId, "ActiveConstumptionEnergy"))
				.thenAccept(totalEnergyOpt -> {

					if (totalEnergyOpt.isPresent()) {
						try {
							this._setActiveConsumptionEnergy(
									TypeUtils.getAsType(OpenemsType.LONG, totalEnergyOpt.get()));
						} catch (IllegalArgumentException e) {
							this._setActiveConsumptionEnergy(TypeUtils.getAsType(OpenemsType.LONG, 0L));
						}
					} else {
						this._setActiveConsumptionEnergy(TypeUtils.getAsType(OpenemsType.LONG, 0L));
					}
				});
	}

	@Override
	protected void deactivate() {
		super.deactivate();
	}

	/**
	 * New session started.
	 * 
	 * @param server    the {@link OcppServer}
	 * @param sessionId the session {@link UUID}
	 */
	public void newSession(OcppServer server, UUID sessionId) {
		this.ocppServer = server;
		this.sessionId = sessionId;
		this._setStatus(Status.NOT_READY_FOR_CHARGING);
		this._setChargingstationCommunicationFailed(false);
	}

	/**
	 * Session lost.
	 */
	public void lostSession() {
		this.ocppServer = null;
		this.sessionId = null;
		this._setStatus(Status.UNDEFINED);
		this._setChargingstationCommunicationFailed(true);
	}

	/**
	 * Get the supported measurements.
	 * 
	 * @return a Set of {@link OcppInformations}
	 */
	public abstract Set<OcppInformations> getSupportedMeasurements();

	/**
	 * Get configured OCPP-ID.
	 * 
	 * @return the OCPP-ID
	 */
	public abstract String getConfiguredOcppId();

	/**
	 * Get configured Connector-ID.
	 * 
	 * @return the Connector-ID
	 */
	public abstract Integer getConfiguredConnectorId();

	/**
	 * Get configured maximum hardware power.
	 * 
	 * @return the maximum hardware power
	 */
	public abstract Integer getConfiguredMaximumHardwarePower();

	/**
	 * Get configured minimum hardware power.
	 * 
	 * @return the minimum hardware power
	 */
	public abstract Integer getConfiguredMinimumHardwarePower();

	/**
	 * Returns the Session Energy.
	 * 
	 * @return true if yes
	 */
	public abstract boolean returnsSessionEnergy();

	/**
	 * Required requests that should be sent after a connection was established.
	 *
	 * @return List of requests
	 */
	public abstract List<Request> getRequiredRequestsAfterConnection();

	/**
	 * Required requests that should be sent permanently during a session.
	 *
	 * @return List of requests
	 */
	public abstract List<Request> getRequiredRequestsDuringConnection();

	/**
	 * Default requests that every OCPP EVCS should have.
	 *
	 * @return OcppRequests
	 */
	public abstract OcppStandardRequests getStandardRequests();

	public UUID getSessionId() {
		return this.sessionId;
	}

	/**
	 * Reset the measured channel values and the charge power.
	 */
	private void resetMeasuredChannelValues() {
		for (MeasuringEvcs.ChannelId c : MeasuringEvcs.ChannelId.values()) {
			Channel<?> channel = this.channel(c);
			channel.setNextValue(null);
		}
		this._setChargePower(0);
	}

	/**
	 * Check the current state and resets the measured values.
	 */
	private void checkCurrentState() {
		var state = this.getStatus();
		switch (state) {
		case CHARGING:
			break;
		case CHARGING_FINISHED:
			this.resetMeasuredChannelValues();
			break;
		case CHARGING_REJECTED:
		case ENERGY_LIMIT_REACHED:
		case ERROR:
		case NOT_READY_FOR_CHARGING:
		case READY_FOR_CHARGING:
		case STARTING:
		case UNDEFINED:
			this._setChargePower(0);
			break;
		}
	}

	public ChargingProperty getLastChargingProperty() {
		return this.lastChargingProperty;
	}

	public void setLastChargingProperty(ChargingProperty chargingProperty) {
		this.lastChargingProperty = chargingProperty;
	}

	public ChargeSessionStamp getSessionStart() {
		return this.sessionStart;
	}

	public ChargeSessionStamp getSessionEnd() {
		return this.sessionEnd;
	}

	@Override
	protected void logInfo(Logger log, String message) {
		super.logInfo(log, message);
	}

	@Override
	protected void logWarn(Logger log, String message) {
		super.logWarn(log, message);
	}

	@Override
	public String debugLog() {
		if (this instanceof ManagedEvcs) {
			return "Limit:" + ((ManagedEvcs) this).getSetChargePowerLimit().orElse(null) + "|"
					+ this.getStatus().getName();
		}
		return "Power:" + this.getChargePower().orElse(0) + "|" + this.getStatus().getName();
	}
}
