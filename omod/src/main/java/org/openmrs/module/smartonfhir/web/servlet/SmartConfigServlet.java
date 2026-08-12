/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.smartonfhir.web.servlet;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.openmrs.module.smartonfhir.model.SmartConformance;
import org.openmrs.module.smartonfhir.model.SmartOAuth2Config;
import org.openmrs.module.smartonfhir.util.SmartAccessTokenVerifierHolder;
import org.openmrs.module.smartonfhir.util.SmartOAuth2ConfigHolder;

@Slf4j
public class SmartConfigServlet extends HttpServlet {

	private static final ObjectMapper objectMapper = new ObjectMapper();

	/**
	 * Only what this server can actually do. A discovery document is a contract, and an app that
	 * believes an unimplemented capability fails in a way that looks like the app's fault.
	 * <p>
	 * {@code launch-standalone} and {@code context-standalone-patient} are claimed because the flow has
	 * been walked end to end in a browser: an app is redirected to the authorization server, the
	 * clinician signs in with their own OpenMRS credentials, chooses a patient, and the token response
	 * carries that patient as launch context. They were absent until that was true.
	 * <p>
	 * Deliberately absent:
	 * <ul>
	 * <li>{@code permission-v2}, because granular scopes are parsed but not enforced. Enforcement
	 * belongs in the FHIR resource providers, not in this module.</li>
	 * </ul>
	 */
	private static final String[] CAPABILITIES = new String[] { "launch-ehr", "launch-standalone", "client-public",
	        "client-confidential-symmetric", "context-ehr-patient", "context-ehr-encounter", "context-standalone-patient",
	        "permission-patient", "permission-user", "sso-openid-connect" };

	private SmartConformance smartConformance;

	@Override
	public void init() {
		final SmartOAuth2Config config = SmartOAuth2ConfigHolder.getConfig();

		if (config == null) {
			log.error("SMART on FHIR is not configured; the discovery document will not be served");
			return;
		}

		smartConformance = buildConformance(config);
	}

	/**
	 * Builds the SMART discovery document from the configured authorization server.
	 * <p>
	 * Endpoints not stated in the configuration are derived from the issuer using OpenID Connect's
	 * conventional paths. That derivation is Keycloak-shaped; the endpoints exist in the configuration
	 * so a deployment on another authorization server can state them instead. Reading them from the
	 * issuer's own discovery document is the better answer and belongs with the SMART 2.x discovery
	 * work.
	 */
	private SmartConformance buildConformance(SmartOAuth2Config config) {
		final String issuer = config.getIssuer().replaceAll("/+$", "");

		SmartConformance conformance = new SmartConformance();
		conformance.setAuthorizationEndpoint(
		    orDerived(config.getAuthorizationEndpoint(), issuer, "/protocol/openid-connect/auth"));
		conformance.setTokenEndpoint(orDerived(config.getTokenEndpoint(), issuer, "/protocol/openid-connect/token"));
		conformance.setIntrospectionEndpoint(
		    orDerived(config.getIntrospectionEndpoint(), issuer, "/protocol/openid-connect/token/introspect"));
		conformance
		        .setRevocationEndpoint(orDerived(config.getRevocationEndpoint(), issuer, "/protocol/openid-connect/revoke"));
		conformance.setRegistrationEndpoint(config.getRegistrationEndpoint());
		conformance.setTokenEndpointAuthMethodsSupported(new String[] { "client_secret_basic", "private_key_jwt" });
		conformance.setIssuer(issuer);
		// What an app is told, which is not necessarily where we fetch keys from: see
		// SmartOAuth2Config.advertisedJwksUri.
		conformance.setJwksUri(config.getAdvertisedJwksUri() != null && !config.getAdvertisedJwksUri().trim().isEmpty()
		        ? config.getAdvertisedJwksUri().trim()
		        : SmartAccessTokenVerifierHolder.getResolvedJwksUri());
		conformance.setGrantTypesSupported(new String[] { "authorization_code", "refresh_token" });
		// SMART App Launch 2.x mandates S256 and forbids plain, so only S256 is offered.
		conformance.setCodeChallengeMethodsSupported(new String[] { "S256" });
		conformance.setScopesSupported(new String[] { "openid", "fhirUser", "launch", "launch/patient", "launch/encounter",
		        "patient/*.rs", "user/*.rs", "offline_access" });
		conformance.setResponseTypesSupported(new String[] { "code" });
		conformance.setCapabilities(CAPABILITIES);

		return conformance;
	}

	private String orDerived(String configured, String issuer, String path) {
		return configured != null && !configured.trim().isEmpty() ? configured : issuer + path;
	}

	public void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
		if (smartConformance == null) {
			// Refuse rather than advertise endpoints nobody configured.
			res.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "SMART on FHIR is not configured");
			return;
		}

		res.setContentType("application/json");
		res.setCharacterEncoding("UTF-8");
		res.setStatus(200);
		objectMapper.writerFor(SmartConformance.class).writeValue(res.getWriter(), smartConformance);
	}
}
