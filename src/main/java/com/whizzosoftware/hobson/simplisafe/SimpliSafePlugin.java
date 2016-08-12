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
import com.whizzosoftware.hobson.api.plugin.http.HttpRequest;
import com.whizzosoftware.hobson.api.plugin.http.HttpResponse;
import com.whizzosoftware.hobson.api.property.PropertyContainer;
import com.whizzosoftware.hobson.api.property.TypedProperty;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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

    /**
     * Returns the plugin name.
     *
     * @return a String
     */
    @Override
    public String getName() {
        return "SimpliSafe";
    }

    /**
     * Indicates how often the onRefresh method will be called.
     *
     * @return number of seconds
     */
    @Override
    public long getRefreshInterval() {
        return 10; // seconds
    }

    /**
     * Returns the list of supported plugin configuration properties.
     *
     * @return a TypedProperty[] (or null if no properties are supported)
     */
    @Override
    protected TypedProperty[] createSupportedProperties() {
        return new TypedProperty[] {
                new TypedProperty.Builder("username", "Username", "Your SimpliSafe account username", TypedProperty.Type.STRING).build(),
                new TypedProperty.Builder("password", "Password", "Your SimpliSafe account password", TypedProperty.Type.SECURE_STRING).build(),
        };
    }

    /**
     * Called when the runtime starts the plugin.
     *
     * @param config the current plugin configuration
     */
    @Override
    public void onStartup(PropertyContainer config) {
        logger.debug("SimpliSafe plugin is starting");
        // attempt to process in case the configuration is already valid
        processConfiguration(config);
    }

    /**
     * Called when the runtime is shutting down the plugin.
     */
    @Override
    public void onShutdown() {
        logger.debug("SimpliSafe plugin has shut down");
    }

    /**
     * Called by the runtime any time the plugin's configuration changes.
     *
     * @param config the new configuration
     */
    @Override
    public void onPluginConfigurationUpdate(PropertyContainer config) {
        processConfiguration(config);
    }

    /**
     * Called by the runtime every getRefreshInterval() seconds to allow the plugin to update device status
     * and perform housekeeping.
     */
    @Override
    public void onRefresh() {
        // if credentials are set but there's no active session, attempt to login
        if (hasCredentials() && !hasSession()) {
            performLoginRequest();
        // if there's a valid session but no base stations have been found, do a location query
        } else if (hasSession() && !hasBaseStations()) {
            performLocationsRequest();
        // otherwise, if there's a valid session, give all known base stations an opportunity to update their state
        } else if (hasSession()) {
            for (SimpliSafeBaseStation c : baseStationMap.values()) {
                c.onRefresh();
            }
        }
    }

    /**
     * Called by the runtime when an HTTP response is received.
     *
     * @param response the response
     * @param context the context object associated with the request
     */
    @Override
    public void onHttpResponse(HttpResponse response, Object context) {
        String ctx = (String)context;

        try {
            String s = response.getBody();
            logger.trace("Received HTTP response ({}): {}", response.getStatusCode(), s);
            switch (response.getStatusCode()) {
                case 200:
                    if (CTX_LOGIN.equals(ctx)) {
                        processLoginResponse(response.getCookies(), parseJSON(s));
                    } else if (CTX_LOCATIONS.equals(ctx)) {
                        processLocationsResponse(parseJSON(s));
                    } else if (ctx.startsWith(CTX_GET_STATE)) {
                        processGetStateResponse(ctx.substring(CTX_GET_STATE.length()), parseJSON(s));
                    } else if (ctx.startsWith(CTX_SET_STATE)) {
                        processSetStateResponse(ctx.substring(CTX_SET_STATE.length()), parseJSON(s));
                    } else {
                        logger.error("Received unrecognized response type: {}", context);
                    }
                    break;
                case 401:
                    if (CTX_LOGIN.equals(ctx)) {
                        invalidateCredentials();
                    } else {
                        logger.error("Detected invalid session; will login again");
                        clearSession();
                        onRefresh();
                    }
                    break;
                default:
                    logger.error("Received unexpected status code for {}: {}", context, response.getStatusCode());
            }
        } catch (IOException e) {
            logger.error("Error processing HTTP response", e);
        }
    }

    /**
     * Called by the runtime when an HTTP request failure is detected.
     *
     * @param cause the cause of the failure
     * @param context the context object associated with the request
     */
    @Override
    public void onHttpRequestFailure(Throwable cause, Object context) {
        logger.error("Request failure for " + context, cause);
    }

    /**
     * Processes a configuration object. This will be called from onStartup() or onPluginConfigurationUpdate().
     *
     * @param config the configuration object to process
     */
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

    /**
     * Send a login request to SimpliSafe.
     */
    protected void performLoginRequest() {
        if (username != null && password != null) {
            try {
                String path = BASE_URL + "/mobile/login";
                String body = "name=" + username + "&pass=" + password + "&device_name=SimpliSafe&device_uuid=" + uuid + "&version=1200&no_persist=1&XDEBUG_SESSION_START=session_name";
                logger.debug("Sending login request to {}: {}", path, body);
                sendHttpRequest(
                    new URI(path),
                    HttpRequest.Method.POST,
                    null,
                    null,
                    body.getBytes(),
                    CTX_LOGIN
                );
            } catch (URISyntaxException e) {
                logger.error("Error logging in", e);
            }
        } else {
            logger.error("Missing username and password; unable to login");
        }
    }

    /**
     * Processes a login response from SimpliSafe.
     *
     * @param cookies any cookies found in the response
     * @param json the JSON-formatted response body
     */
    protected void processLoginResponse(Collection<Cookie> cookies, JSONObject json) {
        logger.trace("Received login response: {} with cookies {}", json, cookies);
        if (json.has("return_code")) {
            switch (json.getInt("return_code")) {
                case 0:
                    invalidateCredentials();
                    break;
                case 1:
                    session = new SimpliSafeSession(json.getString("session"), json.getString("uid"), cookies);
                    logger.debug("Received a successful login for user: {}", json.getString("username"));
                    setStatus(PluginStatus.running());
                    onRefresh(); // force an update
                    break;
                default:
                    logger.error("Received an unexpected login return_code: {}", json.getInt("return_code"));
            }
        } else {
            logger.error("No return_code found in login response: {}", json);
        }
    }

    /**
     * Sends a request for a list of locations to SimpliSafe.
     */
    protected void performLocationsRequest() {
        if (hasSession()) {
            try {
                String path = BASE_URL + "/mobile/" + session.getUid() + "/locations";
                String body = "no_persist=0&XDEBUG_SESSION_START=session_name";
                logger.debug("Sending locations request to {}: {}", path, body);
                sendHttpRequest(
                    new URI(path),
                    HttpRequest.Method.POST,
                    null,
                    session.getCookies(),
                    body.getBytes(),
                    CTX_LOCATIONS
                );
            } catch (URISyntaxException e) {
                logger.error("Error performing location query", e);
            }
        } else {
            logger.error("No login session found; unable to perform location query");
        }
    }

    /**
     * Processes a locations response from SimpliSafe.
     *
     * @param json the JSON-formatted response body
     */
    protected void processLocationsResponse(JSONObject json) {
        logger.trace("Received locations response: {}", json);
        JSONObject locations = json.getJSONObject("locations");
        for (Object o : locations.keySet()) {
            String location = (String)o;
            if (!baseStationMap.containsKey(location)) {
                // we found a new base station
                logger.debug("Publishing base station: {}", location);
                SimpliSafeBaseStation ssc = new SimpliSafeBaseStation(this, location, this);
                publishDevice(ssc);
                baseStationMap.put(location, ssc);
            }
        }
        onRefresh(); // force an update
    }

    /**
     * Sends a request for location state to SimpliSafe.
     *
     * @param location the location for which state is being requested
     */
    @Override
    public void performGetState(String location) {
        if (hasSession() && location != null) {
            logger.trace("Performing get state for {}", location);
            try {
                sendHttpRequest(
                    new URI(BASE_URL + "/mobile/" + session.getUid() + "/sid/" + location + "/get-state"),
                    HttpRequest.Method.POST,
                    null,
                    session.getCookies(),
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

    /**
     * Processes a get state response from SimpliSafe.
     *
     * @param location the location the response is associated with
     * @param json the JSON-formatted response body
     */
    protected void processGetStateResponse(String location, JSONObject json) {
        logger.trace("Received get state response: {}", json);
        SimpliSafeBaseStation c = baseStationMap.get(location);
        if (c != null) {
            c.onState(json);
        } else {
            logger.error("Received state for unknown base station: {}", location);
        }
    }

    /**
     * Sends a request to set location state to SimpliSafe.
     *
     * @param location the location for which state is being set
     * @param state the new state value (off, home, away)
     */
    @Override
    public void performSetState(String location, String state) {
        if (hasSession() && location != null) {
            logger.debug("Performing set state: {}, {}", location, state);
            try {
                sendHttpRequest(
                    new URI(BASE_URL + "/mobile/" + session.getUid() + "/sid/" + location + "/set-state"),
                    HttpRequest.Method.POST,
                    null,
                    session.getCookies(),
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

    /**
     * Processes a set state response from SimpliSafe.
     *
     * @param location the location the response is associated with
     * @param json the JSON-formatted response body
     */
    protected void processSetStateResponse(String location, JSONObject json) {
        logger.trace("Received set state response for {}: {}", location, json);
        // the response body format is identical to "get state" so just call its process method to handle it
        processGetStateResponse(location, json);
    }

    private boolean hasCredentials() {
        return (username != null && password != null);
    }

    private boolean hasSession() {
        return (session != null);
    }

    private void clearSession() {
        session = null;
    }

    private void invalidateCredentials() {
        logger.error("Configured credentials appear to be invalid; resetting them");
        username = null;
        password = null;
        setStatus(PluginStatus.failed("Username and/or password are invalid"));
    }

    private boolean hasBaseStations() {
        return !baseStationMap.isEmpty();
    }

    private JSONObject parseJSON(String s) {
        return new JSONObject(new JSONTokener(s));
    }
}
