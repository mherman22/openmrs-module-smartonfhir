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

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.openmrs.module.smartonfhir.model.SmartApp;
import org.openmrs.util.OpenmrsUtil;

/**
 * The SMART apps this deployment permits to be launched, read from {@code {application data
 * directory}/config/smart-apps.json}.
 * <p>
 * Configuration rather than persistence, matching how the authorization server and the launch
 * secret are configured. Which apps a hospital trusts is a deployment decision, changed rarely and
 * by whoever administers the server — not something to edit in a running system, and not something
 * to migrate.
 * <p>
 * An absent file means no app can be launched. That is the safe direction: the alternative is a
 * launch servlet that accepts an address from whoever calls it, which is what this replaced.
 */
@Slf4j
public class SmartAppRegistry {

	public static final String CONFIG_FILE_NAME = "smart-apps.json";

	private static final ObjectMapper objectMapper = new ObjectMapper();

	private static volatile Map<String, SmartApp> apps;

	private static volatile boolean loadAttempted = false;

	/**
	 * @return the registered apps, in the order the file lists them, as copies. Never null.
	 *         <p>
	 *         Copies, because this used to hand out the stored entries themselves: any caller could do
	 *         {@code getApps().get(0).setLaunchUrl("https://evil/")} and rewrite the deployment's
	 *         allowlist process-wide. That is a poor property for the type whose whole purpose is
	 *         deciding where a launch may be sent.
	 */
	public static List<SmartApp> getApps() {
		List<SmartApp> copies = new ArrayList<>();

		for (SmartApp app : registry().values()) {
			copies.add(copyOf(app));
		}

		return copies;
	}

	/** @return the app with this id as a copy, or null if no such app is registered. */
	private static SmartApp copyOf(SmartApp app) {
		SmartApp copy = new SmartApp();
		copy.setId(app.getId());
		copy.setName(app.getName());
		copy.setDescription(app.getDescription());
		copy.setLaunchUrl(app.getLaunchUrl());
		copy.setClientId(app.getClientId());
		copy.setLaunchContext(app.getLaunchContext());

		return copy;
	}

	/** @return the app with this id, or null if no such app is registered. */
	public static SmartApp getApp(String id) {
		if (id == null || id.trim().isEmpty()) {
			return null;
		}

		SmartApp app = registry().get(id.trim());

		return app == null ? null : copyOf(app);
	}

	/**
	 * Discards what was loaded, so the next read picks up an edited file. For tests, and for reloading.
	 */
	public static synchronized void reset() {
		apps = null;
		loadAttempted = false;
	}

	private static Map<String, SmartApp> registry() {
		if (!loadAttempted) {
			synchronized (SmartAppRegistry.class) {
				if (!loadAttempted) {
					load();
					// Only latch on success, as SmartOAuth2ConfigHolder does: otherwise a
					// transiently unreadable file left no app launchable until a restart.
					loadAttempted = apps != null;
				}
			}
		}

		return apps == null ? Collections.emptyMap() : apps;
	}

	private static void load() {
		final File file = configFile();

		if (!file.canRead()) {
			log.info("No SMART app registry at {}, so no app can be launched from the EHR. Create it to register one.",
			    file.getAbsolutePath());
			return;
		}

		try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
			SmartAppRegistryFile loaded = objectMapper.readValue(in, SmartAppRegistryFile.class);
			Map<String, SmartApp> byId = new LinkedHashMap<>();

			for (SmartApp app : loaded.getApps()) {
				if (!app.isUsable()) {
					// Listing it would show a clinician an app that fails when chosen.
					log.error("Ignoring an entry in {} with no id or no launchUrl", file.getAbsolutePath());
					continue;
				}
				if (byId.putIfAbsent(app.getId().trim(), app) != null) {
					log.error("Ignoring a duplicate app id '{}' in {}", app.getId(), file.getAbsolutePath());
				}
			}

			apps = byId;
			log.info("Registered {} SMART app(s) from {}", byId.size(), file.getAbsolutePath());
		}
		catch (Exception e) {
			// Deliberately not a partial load: half a registry is harder to diagnose than none.
			log.error("Could not read {}; no app will be launchable until it parses", file.getAbsolutePath(), e);
		}
	}

	private static File configFile() {
		return Paths.get(OpenmrsUtil.getApplicationDataDirectory(), "config", CONFIG_FILE_NAME).toFile();
	}

	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	static class SmartAppRegistryFile {

		@JsonProperty("apps")
		private List<SmartApp> apps = new ArrayList<>();
	}
}
