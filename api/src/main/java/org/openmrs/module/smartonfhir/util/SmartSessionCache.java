/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.smartonfhir.util;

import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.openmrs.module.smartonfhir.model.SmartSession;

public class SmartSessionCache {

	/**
	 * Built once, eagerly, and never reassigned.
	 * <p>
	 * This was a lazily initialised non-volatile static assigned from a constructor that runs per
	 * request, so two threads racing at startup could build two caches and publish one: a handle issued
	 * into the discarded cache then redeemed as "Unknown launch". Non-volatile publication also allowed
	 * another thread to observe a partly constructed cache.
	 */
	private static final Cache<String, SmartSession> CACHE = Caffeine.newBuilder().expireAfterWrite(5, TimeUnit.MINUTES)
	        .maximumSize(500).recordStats().build();

	/**
	 * Removes the entry and returns what it held, in one operation.
	 * <p>
	 * Redemption used to be a {@code get} followed by a {@code clear}, which is single-use only when
	 * single-threaded: two concurrent redemptions of one handle could both see a session and both
	 * proceed.
	 */
	public SmartSession take(String key) {
		return key == null ? null : CACHE.asMap().remove(key);
	}

	public boolean put(String key, SmartSession value) {
		CACHE.put(key, value);
		return Boolean.TRUE;
	}

	public SmartSession get(String key) {
		try {
			return CACHE.getIfPresent(key);
		}
		catch (Exception e) {
			return null;
		}
	}

	public boolean clear(String key) {
		CACHE.invalidate(key);

		return Boolean.TRUE;
	}
}
