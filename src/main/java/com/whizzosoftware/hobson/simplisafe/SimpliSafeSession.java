/*******************************************************************************
 * Copyright (c) 2016 Whizzo Software, LLC.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package com.whizzosoftware.hobson.simplisafe;

import com.whizzosoftware.hobson.api.plugin.http.Cookie;

import java.util.Collection;

/**
 * Represents a SimpliSafe user session.
 *
 * @author Dan Noguerol
 */
public class SimpliSafeSession {
    private String session;
    private String uid;
    private Collection<Cookie> cookies;

    public SimpliSafeSession(String session, String uid, Collection<Cookie> cookies) {
        this.session = session;
        this.uid = uid;
        this.cookies = cookies;
    }

    public String getSession() {
        return session;
    }

    public String getUid() {
        return uid;
    }

    public Collection<Cookie> getCookies() {
        return cookies;
    }
}
