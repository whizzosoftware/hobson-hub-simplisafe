/*******************************************************************************
 * Copyright (c) 2016 Whizzo Software, LLC.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package com.whizzosoftware.hobson.simplisafe;

import com.whizzosoftware.hobson.api.device.AbstractHobsonDevice;
import com.whizzosoftware.hobson.api.device.DeviceType;
import com.whizzosoftware.hobson.api.plugin.HobsonPlugin;
import com.whizzosoftware.hobson.api.property.PropertyContainer;
import com.whizzosoftware.hobson.api.property.TypedProperty;
import com.whizzosoftware.hobson.api.variable.HobsonVariable;
import com.whizzosoftware.hobson.api.variable.VariableConstants;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A device representing a SimpliSafe base station. This manages the state of the overall system
 * (e.g. whether it is armed or not).
 *
 * @author Dan Noguerol
 */
public class SimpliSafeBaseStation extends AbstractHobsonDevice {
    private static final Logger logger = LoggerFactory.getLogger(SimpliSafeBaseStation.class);

    public static final int STATE_OFF = 2;
    public static final int STATE_HOME = 4;
    public static final int STATE_AWAY = 5;

    private SimpliSafeClient client;

    /**
     * Constructor.
     *
     * @param plugin the plugin associated with this device
     * @param id the device ID
     * @param client a client to use for making SimpliSafe requests
     */
    public SimpliSafeBaseStation(HobsonPlugin plugin, String id, SimpliSafeClient client) {
        super(plugin, id);
        this.client = client;
        setDefaultName("SimpliSafe (" + id + ")");
    }

    @Override
    public DeviceType getType() {
        return DeviceType.SECURITY_PANEL;
    }

    @Override
    protected TypedProperty[] createSupportedProperties() {
        return null;
    }

    @Override
    public String getPreferredVariableName() {
        return VariableConstants.ARMED;
    }

    @Override
    public void onStartup(PropertyContainer config) {
        super.onStartup(config);

        publishVariable(VariableConstants.ARMED, null, HobsonVariable.Mask.READ_WRITE, null);
    }

    @Override
    public void onShutdown() {
    }

    /**
     * Called by the plugin to allow this device to update its state.
     */
    public void onRefresh() {
        client.performGetState(getContext().getDeviceId());
    }

    /**
     * Called by the plugin when a state response for this device is received.
     * @param json
     */
    public void onState(JSONObject json) {
        logger.trace("{} received state JSON: {}", getContext().getDeviceId(), json);
        if (json.has("response_code")) {
            int code = json.getInt("response_code");
            fireVariableUpdateNotification(VariableConstants.ARMED, (code == STATE_AWAY));
        } else {
            logger.error("Received unexpected get status response: {}", json);
        }
    }

    /**
     * Called by the runtime when a request to set a variable for this device is received.
     *
     * @param name the variable name
     * @param value the new variable value
     */
    @Override
    public void onSetVariable(String name, Object value) {
        logger.debug("Variable {} changed to {}", name, value);
        // the only variable we currently care about is "ARMED"
        if (VariableConstants.ARMED.equals(name) && value instanceof Boolean) {
            // send the request to SimpliSafe to change the state
            client.performSetState(getContext().getDeviceId(), ((Boolean)value) ? "away" : "home");
        }
    }
}
