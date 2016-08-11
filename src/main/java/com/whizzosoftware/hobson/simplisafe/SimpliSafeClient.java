/*******************************************************************************
 * Copyright (c) 2016 Whizzo Software, LLC.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package com.whizzosoftware.hobson.simplisafe;

/**
 * An interface used by SimpliSafe controller devices to get/set their state.
 *
 * @author Dan Noguerol
 */
public interface SimpliSafeClient {
    void performGetState(String location);
    void performSetState(String location, String state);
}
