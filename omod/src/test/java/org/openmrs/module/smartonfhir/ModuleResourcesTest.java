/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.smartonfhir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.xml.parsers.DocumentBuilderFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The module's XML resources are parsed by OpenMRS at startup, not by the compiler, so a malformed
 * one builds and packages perfectly and then fails at runtime.
 * <p>
 * It fails badly, too. A Spring context that will not parse stops this module starting, and OpenMRS
 * abandons module startup altogether: a single bad character here took all 31 modules of RefApp
 * 3.7.1 down at once, leaving the REST API answering 404. These tests exist because that happened.
 */
class ModuleResourcesTest {

	/** XML forbids "--" inside a comment. It is easy to type and invisible until startup. */
	private static final Pattern COMMENT = Pattern.compile("<!--(.*?)-->", Pattern.DOTALL);

	static List<Path> xmlResources() throws IOException {
		List<Path> roots = new ArrayList<>();
		for (String candidate : new String[] { "src/main/resources", "omod/src/main/resources", "api/src/main/resources",
		        "../api/src/main/resources" }) {
			Path path = Paths.get(candidate);
			if (Files.isDirectory(path)) {
				roots.add(path);
			}
		}

		List<Path> found = new ArrayList<>();
		for (Path root : roots) {
			try (Stream<Path> walk = Files.walk(root)) {
				found.addAll(walk.filter(p -> p.toString().endsWith(".xml")).collect(Collectors.toList()));
			}
		}

		assertFalse(found.isEmpty(), "no XML resources found; this test would silently pass");

		return found;
	}

	@MethodSource("xmlResources")
	@ParameterizedTest(name = "{0}")
	@DisplayName("parses as XML")
	void parsesAsXml(Path resource) {
		assertDoesNotThrow(() -> {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			// OpenMRS resources reference DTDs by URL; resolving them would make this test depend on
			// the network, and parsing is what is under test here, not validation.
			factory.setValidating(false);
			factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
			factory.newDocumentBuilder().parse(resource.toFile());
		}, resource + " does not parse, so OpenMRS will fail to start this module");
	}

	@MethodSource("xmlResources")
	@ParameterizedTest(name = "{0}")
	@DisplayName("contains no double hyphen inside a comment")
	void hasNoDoubleHyphenInComments(Path resource) throws IOException {
		String contents = new String(Files.readAllBytes(resource), StandardCharsets.UTF_8);
		Matcher matcher = COMMENT.matcher(contents);

		while (matcher.find()) {
			String body = matcher.group(1);
			assertFalse(body.contains("--"),
			    resource + " has a comment containing \"--\", which XML forbids: " + body.replace('\n', ' ').trim());
		}
	}

	@Test
	@DisplayName("the module config declares only dependencies RefApp 3.7.1 actually ships")
	void requiredModulesAreAvailableInTheDistribution() throws IOException {
		Path config = xmlResources().stream().filter(p -> p.getFileName().toString().equals("config.xml")).findFirst()
		        .orElseThrow(() -> new AssertionError("config.xml not found"));
		String contents = new String(Files.readAllBytes(config), StandardCharsets.UTF_8);

		// A require_module OpenMRS cannot satisfy prevents this module from starting at all.
		Matcher matcher = Pattern.compile("<require_module[^>]*>([^<]+)</require_module>").matcher(contents);
		List<String> required = new ArrayList<>();
		while (matcher.find()) {
			required.add(matcher.group(1).trim());
		}

		assertFalse(required.isEmpty(), "the module should declare its dependencies");

		for (String module : required) {
			assertTrue(module.equals("org.openmrs.module.fhir2") || module.equals("org.openmrs.module.authentication"),
			    "config.xml requires '" + module + "', which is not part of RefApp 3.7.1. The RefApp 2.x UI modules "
			            + "in particular (uiframework, appframework, coreapps, appui) are absent from 3.x.");
		}
	}
}
