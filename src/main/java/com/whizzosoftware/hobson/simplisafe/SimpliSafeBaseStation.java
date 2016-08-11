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

    private SimpliSafeClient client;

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
    public void onStartup(PropertyContainer config) {
        super.onStartup(config);

        publishVariable(VariableConstants.ARMED, null, HobsonVariable.Mask.READ_WRITE, null);
    }

    @Override
    public void onShutdown() {

    }

    public void onRefresh() {
        client.performGetState(getContext().getDeviceId());
    }

    public void onState(JSONObject json) {
        logger.trace("{} received state JSON: {}", getContext().getDeviceId(), json);
        if (json.has("response_code")) {
            int code = json.getInt("response_code");
            fireVariableUpdateNotification(VariableConstants.ARMED, (code == 3));
        } else {
            logger.error("Received unexpected get status response: {}", json);
        }
    }

    @Override
    public void onSetVariable(String name, Object value) {
        logger.debug("Variable {} changed to {}", name, value);
        if (VariableConstants.ARMED.equals(name) && value instanceof Boolean) {
            client.performSetState(getContext().getDeviceId(), ((Boolean)value) ? "away" : "home");
        }
    }
}
