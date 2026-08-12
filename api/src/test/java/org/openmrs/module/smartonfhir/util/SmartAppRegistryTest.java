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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openmrs.module.smartonfhir.model.SmartApp;
import org.openmrs.util.OpenmrsUtil;

/**
 * The registry decides where an EHR launch sends a clinician. Before it existed the launch servlet
 * took that address from a request parameter, so anyone who could reach it could have a single-use
 * launch handle delivered to a host of their choosing. What matters here is that an app the
 * deployment has not registered cannot be launched, and that a malformed entry is refused rather
 * than half-honoured.
 */
class SmartAppRegistryTest {

	@TempDir
	Path appData;

	private String previousAppDataDirectory;

	@BeforeEach
	void useATemporaryApplicationDataDirectory() {
		previousAppDataDirectory = OpenmrsUtil.getApplicationDataDirectory();
		OpenmrsUtil.setApplicationDataDirectory(appData.toString());
		SmartAppRegistry.reset();
	}

	@AfterEach
	void restore() {
		OpenmrsUtil.setApplicationDataDirectory(previousAppDataDirectory);
		SmartAppRegistry.reset();
	}

	private void writeRegistry(String json) throws Exception {
		File config = appData.resolve("config").toFile();
		assertTrue(config.mkdirs() || config.isDirectory());
		Files.write(config.toPath().resolve(SmartAppRegistry.CONFIG_FILE_NAME), json.getBytes(StandardCharsets.UTF_8));
		SmartAppRegistry.reset();
	}

	@Nested
	@DisplayName("with no registry file")
	class Absent {

		/**
		 * No file must mean no launchable app. The alternative — falling back to an address supplied by the
		 * caller — is exactly the open redirector this replaced.
		 */
		@Test
		@DisplayName("nothing can be launched")
		void nothingIsLaunchable() {
			assertTrue(SmartAppRegistry.getApps().isEmpty());
			assertNull(SmartAppRegistry.getApp("growth-chart"));
		}
	}

	@Nested
	@DisplayName("with a registry")
	class Present {

		@BeforeEach
		void registerTwoApps() throws Exception {
			writeRegistry("{\"apps\":[" + "{\"id\":\"growth-chart\",\"name\":\"Growth Chart\","
			        + "\"launchUrl\":\"https://growth.example.org/launch\",\"clientId\":\"growth-chart\"},"
			        + "{\"id\":\"risk\",\"name\":\"Risk Dashboard\",\"launchUrl\":\"https://risk.example.org/launch\","
			        + "\"launchContext\":\"encounter\"}]}");
		}

		@Test
		@DisplayName("a registered app is found by id, with its address")
		void registeredAppIsFound() {
			SmartApp app = SmartAppRegistry.getApp("growth-chart");

			assertNotNull(app);
			assertEquals("Growth Chart", app.getName());
			assertEquals("https://growth.example.org/launch", app.getLaunchUrl());
			assertEquals("patient", app.getLaunchContext(), "patient context is the default");
		}

		@Test
		@DisplayName("launch context is read when stated")
		void launchContextIsRead() {
			assertEquals("encounter", SmartAppRegistry.getApp("risk").getLaunchContext());
		}

		@Test
		@DisplayName("apps keep the order the file lists them in")
		void orderIsPreserved() {
			assertEquals("growth-chart", SmartAppRegistry.getApps().get(0).getId());
			assertEquals("risk", SmartAppRegistry.getApps().get(1).getId());
		}

		@Test
		@DisplayName("an app that was never registered is not found")
		void unregisteredAppIsNotFound() {
			assertNull(SmartAppRegistry.getApp("something-else"));
		}

		@Test
		@DisplayName("an id is matched exactly, not by prefix or case")
		void idIsMatchedExactly() {
			assertNull(SmartAppRegistry.getApp("growth"));
			assertNull(SmartAppRegistry.getApp("GROWTH-CHART"));
			assertNull(SmartAppRegistry.getApp("growth-chart/../risk"));
		}

		@Test
		@DisplayName("a blank or null id is not found")
		void blankIdIsNotFound() {
			assertNull(SmartAppRegistry.getApp(null));
			assertNull(SmartAppRegistry.getApp(""));
			assertNull(SmartAppRegistry.getApp("   "));
		}
	}

	@Nested
	@DisplayName("with a malformed registry")
	class Malformed {

		/** Listing it would offer a clinician an app that fails the moment they choose it. */
		@Test
		@DisplayName("an entry with no launch URL is dropped, and the rest still load")
		void entryWithNoLaunchUrlIsDropped() throws Exception {
			writeRegistry("{\"apps\":[{\"id\":\"broken\",\"name\":\"No URL\"},"
			        + "{\"id\":\"fine\",\"launchUrl\":\"https://fine.example.org/launch\"}]}");

			assertNull(SmartAppRegistry.getApp("broken"));
			assertNotNull(SmartAppRegistry.getApp("fine"));
		}

		@Test
		@DisplayName("an entry with no id is dropped")
		void entryWithNoIdIsDropped() throws Exception {
			writeRegistry("{\"apps\":[{\"name\":\"Anonymous\",\"launchUrl\":\"https://x.example.org/launch\"}]}");

			assertTrue(SmartAppRegistry.getApps().isEmpty());
		}

		/** Which of two entries wins would otherwise depend on file order, silently. */
		@Test
		@DisplayName("a duplicate id keeps the first entry")
		void duplicateIdKeepsTheFirst() throws Exception {
			writeRegistry("{\"apps\":[{\"id\":\"dup\",\"launchUrl\":\"https://first.example.org/launch\"},"
			        + "{\"id\":\"dup\",\"launchUrl\":\"https://second.example.org/launch\"}]}");

			assertEquals("https://first.example.org/launch", SmartAppRegistry.getApp("dup").getLaunchUrl());
			assertEquals(1, SmartAppRegistry.getApps().size());
		}

		/**
		 * Unparseable configuration must not leave half a registry behind: a launch that works for one app
		 * and not another is harder to diagnose than one that works for none.
		 */
		@Test
		@DisplayName("a file that does not parse leaves nothing launchable")
		void unparseableFileLeavesNothing() throws Exception {
			writeRegistry("{\"apps\": [ this is not json");

			assertTrue(SmartAppRegistry.getApps().isEmpty());
			assertNull(SmartAppRegistry.getApp("anything"));
		}

		@Test
		@DisplayName("unknown fields are ignored rather than fatal")
		void unknownFieldsAreIgnored() throws Exception {
			writeRegistry("{\"apps\":[{\"id\":\"ok\",\"launchUrl\":\"https://ok.example.org/launch\","
			        + "\"somethingNew\":\"from a later version\"}],\"alsoNew\":true}");

			assertNotNull(SmartAppRegistry.getApp("ok"));
		}
	}

	@Nested
	@DisplayName("an entry")
	class Usability {

		@Test
		@DisplayName("needs both an id and a launch URL to be usable")
		void needsIdAndLaunchUrl() {
			SmartApp app = new SmartApp();
			assertFalse(app.isUsable());

			app.setId("x");
			assertFalse(app.isUsable(), "an app with no address cannot be launched");

			app.setLaunchUrl("   ");
			assertFalse(app.isUsable(), "whitespace is not an address");

			app.setLaunchUrl("https://x.example.org/launch");
			assertTrue(app.isUsable());
		}
	}
}
