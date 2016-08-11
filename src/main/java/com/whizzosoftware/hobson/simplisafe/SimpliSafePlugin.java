/*******************************************************************************
 * Copyright (c) 2016 Whizzo Software, LLC.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package com.whizzosoftware.hobson.simplisafe;

import com.whizzosoftware.hobson.api.plugin.PluginStatus;
import com.whizzosoftware.hobson.api.plugin.http.AbstractHttpClientPlugin;
import com.whizzosoftware.hobson.api.plugin.http.Cookie;
import com.whizzosoftware.hobson.api.property.PropertyContainer;
import com.whizzosoftware.hobson.api.property.TypedProperty;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;

/**
 * A plugin for SimpliSafe security systems.
 *
 * @author Dan Noguerol
 */
public class SimpliSafePlugin extends AbstractHttpClientPlugin implements SimpliSafeClient {
    private static final Logger logger = LoggerFactory.getLogger(SimpliSafePlugin.class);

    private static final String BASE_URL = "https://simplisafe.com";
    private static final String CTX_LOGIN = "login";
    private static final String CTX_LOCATIONS = "locations";
    private static final String CTX_GET_STATE = "gstate:";
    private static final String CTX_SET_STATE = "sstate:";

    private String username;
    private String password;
    private String uuid = UUID.randomUUID().toString();
    private SimpliSafeSession session;
    private Map<String,SimpliSafeBaseStation> baseStationMap = new HashMap<>();

    public SimpliSafePlugin(String pluginId) {
        super(pluginId);
    }

    @Override
    public String getName() {
        return "SimpliSafe";
    }

    @Override
    public long getRefreshInterval() {
        return 10;
    }

    @Override
    protected TypedProperty[] createSupportedProperties() {
        return new TypedProperty[] {
                new TypedProperty.Builder("username", "Username", "Your SimpliSafe account username", TypedProperty.Type.STRING).build(),
                new TypedProperty.Builder("password", "Password", "Your SimpliSafe account password", TypedProperty.Type.SECURE_STRING).build(),
        };
    }

    @Override
    public void onStartup(PropertyContainer config) {
        logger.debug("SimpliSafe plugin is starting");
        processConfiguration(config);
    }

    @Override
    public void onShutdown() {
        logger.debug("SimpliSafe plugin has shut down");
    }

    @Override
    public void onPluginConfigurationUpdate(PropertyContainer config) {
        processConfiguration(config);
    }

    @Override
    public void onRefresh() {
        // if there's not currently a session, perform login
        if (!hasSession()) {
            performLoginRequest();
        // if no base stations have been found, do a location query
        } else if (!hasBaseStations()) {
            performLocationsRequest();
        // otherwise, allow all known base stations to update their state
        } else {
            for (SimpliSafeBaseStation c : baseStationMap.values()) {
                c.onRefresh();
            }
        }
    }

    @Override
    protected void onHttpResponse(int statusCode, Map<String, List<String>> headers, List<Cookie> cookies, String response, Object context) {
        if (statusCode == 200) {
            String ctx = (String)context;
            if (CTX_LOGIN.equals(ctx)) {
                processLoginResponse(cookies, parseJSON(response));
            } else if (CTX_LOCATIONS.equals(ctx)) {
                processLocationsResponse(parseJSON(response));
            } else if (ctx.startsWith(CTX_GET_STATE)) {
                processGetStateResponse(ctx.substring(CTX_GET_STATE.length()), parseJSON(response));
            } else if (ctx.startsWith(CTX_SET_STATE)) {
                processSetStateResponse(ctx.substring(CTX_SET_STATE.length()), parseJSON(response));
            } else {
                logger.error("Received unrecognized response type: {}", context);
            }
        } else {
            logger.error("Received unexpected status code for {}: {}", context, statusCode);
        }
    }

    @Override
    protected void onHttpRequestFailure(Throwable cause, Object context) {
        logger.error("Request failure for: " + context, cause);
    }

    protected void processConfiguration(PropertyContainer config) {
        String u = config.getStringPropertyValue("username");
        String p = config.getStringPropertyValue("password");

        if (u != null && p != null && !u.equals(username) && !p.equals(username)) {
            logger.debug("Username and password has changed");
            clearSession();
            username = u;
            password = p;
            onRefresh(); // force an update
        } else if (username == null && password == null) {
            setStatus(PluginStatus.notConfigured("Username and password not configured"));
        }
    }

    protected void performLoginRequest() {
        if (username != null && password != null) {
            try {
                String entity = "name=" + username + "&pass=" + password + "&device_name=SimpliSafe&device_uuid=" + uuid + "&version=1200&no_persist=1&XDEBUG_SESSION_START=session_name";
                logger.debug("Sending login request: {}", entity);
                sendHttpPostRequest(
                    new URI(BASE_URL + "/mobile/login"),
                    null,
                    entity.getBytes(),
                    CTX_LOGIN
                );
            } catch (URISyntaxException e) {
                logger.error("Error logging in", e);
            }
        } else {
            logger.error("Missing username and password; unable to login");
        }
    }

    protected void processLoginResponse(List<Cookie> cookies, JSONObject json) {
        if (json.getInt("return_code") == 1) {
            session = new SimpliSafeSession(json.getString("session"), json.getString("uid"), cookies);
            logger.debug("Received a successful login for user: {}", json.getString("username"));
            setStatus(PluginStatus.running());
            onRefresh(); // force an update
        } else {
            logger.error("Received an unexpected return_code: {}", json.getInt("return_code"));
        }
    }

    protected void performLocationsRequest() {
        if (hasSession()) {
            try {
                sendHttpPostRequest(
                    new URI(BASE_URL + "/mobile/" + session.getUid() + "/locations"),
                    Collections.singletonMap("Cookie", session.getCookieString()),
                    ("no_persist=0&XDEBUG_SESSION_START=session_name").getBytes(),
                    CTX_LOCATIONS
                );
            } catch (URISyntaxException e) {
                logger.error("Error performing location query", e);
            }
        } else {
            logger.error("No login session found; unable to perform location query");
        }
    }

    protected void processLocationsResponse(JSONObject json) {
        JSONObject locations = json.getJSONObject("locations");
        for (Object o : locations.keySet()) {
            String location = (String)o;
            if (!baseStationMap.containsKey(location)) {
                logger.debug("Publishing base station: {}", location);
                SimpliSafeBaseStation ssc = new SimpliSafeBaseStation(this, location, this);
                publishDevice(ssc);
                baseStationMap.put(location, ssc);
            }
        }
        onRefresh(); // force an update
    }

    @Override
    public void performGetState(String location) {
        if (hasSession() && location != null) {
            logger.trace("Performing get state for {}", location);
            try {
                sendHttpPostRequest(
                        new URI(BASE_URL + "/mobile/" + session.getUid() + "/sid/" + location + "/get-state"),
                        Collections.singletonMap("Cookie", session.getCookieString()),
                        ("no_persist=0&XDEBUG_SESSION_START=session_name").getBytes(),
                        CTX_GET_STATE + location
                );
            } catch (URISyntaxException e) {
                logger.error("Error performing get state query", e);
            }
        } else {
            logger.error("Full login information not available; unable to perform status query");
        }
    }

    @Override
    public void performSetState(String location, String state) {
        if (hasSession() && location != null) {
            logger.debug("Performing set state: {}, {}", location, state);
            try {
                sendHttpPostRequest(
                        new URI(BASE_URL + "/mobile/" + session.getUid() + "/sid/" + location + "/set-state"),
                        Collections.singletonMap("Cookie", session.getCookieString()),
                        ("state=" + state + "&mobile=1&no_persist=0&XDEBUG_SESSION_START=session_name").getBytes(),
                        CTX_SET_STATE + location
                );
            } catch (URISyntaxException e) {
                logger.error("Error performing get state query", e);
            }
        } else {
            logger.error("Full login information not available; unable to set state");
        }
    }

    protected void processGetStateResponse(String location, JSONObject json) {
        SimpliSafeBaseStation c = baseStationMap.get(location);
        if (c != null) {
            c.onState(json);
        } else {
            logger.error("Received state for unknown base station: {}", location);
        }
    }

    protected void processSetStateResponse(String location, JSONObject json) {
        logger.debug("Successfully set state for {}", location);
        processGetStateResponse(location, json);
    }

    private boolean hasSession() {
        return (session != null);
    }

    private void clearSession() {
        session = null;
    }

    private boolean hasBaseStations() {
        return !baseStationMap.isEmpty();
    }

    private JSONObject parseJSON(String s) {
        return new JSONObject(new JSONTokener(s));
    }
}
