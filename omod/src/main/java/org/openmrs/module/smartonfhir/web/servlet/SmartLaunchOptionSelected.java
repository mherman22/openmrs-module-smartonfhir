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
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import com.nimbusds.jwt.JWTClaimsSet;
import lombok.extern.slf4j.Slf4j;
import org.openmrs.module.smartonfhir.util.SmartLaunchTokens;
import org.openmrs.module.smartonfhir.util.SmartSecretKeyHolder;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

@Slf4j
public class SmartLaunchOptionSelected extends HttpServlet {

	public void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
		String token = getParameter(req, "token");
		String patientId = getParameter(req, "patientId");
		String visitId = getParameter(req, "visitId");
		String decodedUrl = URLDecoder.decode(token, StandardCharsets.UTF_8.name());

		String jwtKeyToken = null;
		try {
			jwtKeyToken = getParameterFromStringUrl(decodedUrl, "key");
		}
		catch (URISyntaxException e) {
			log.error("Verification exception while trying to determine launchType", e);
			return;
		}

		String launchTypeString = getLaunchTypeString(jwtKeyToken);

		if (launchTypeString == null) {
			res.sendError(HttpServletResponse.SC_FORBIDDEN, "Couldn't found scope in Token");
			return;
		}

		if (launchTypeString.contains("encounter") && visitId == null) {
			res.sendRedirect(res.encodeRedirectURL(
			    req.getContextPath() + "/smartonfhir/findVisit.page?app=smartonfhir.search.visit&patientId=" + patientId
			            + "&token=" + URLEncoder.encode(token, StandardCharsets.UTF_8.name())));
			return;
		}

		if (token == null || (patientId == null && visitId == null)) {
			res.sendError(HttpServletResponse.SC_BAD_REQUEST);
			return;
		}

		JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder();

		if (patientId != null) {
			claims.claim("patient", patientId);
		}
		if (visitId != null) {
			claims.claim("visit", visitId);
		}

		String appToken = SmartLaunchTokens.sign(claims.build(), SmartSecretKeyHolder.getSecretKey());

		if (appToken == null) {
			res.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Could not issue the launch token");
			return;
		}

		String encodedToken = URLEncoder.encode(appToken, StandardCharsets.UTF_8.name());

		res.sendRedirect(decodedUrl.replace("{APP_TOKEN}", encodedToken));
	}

	private String getParameter(HttpServletRequest request, String parameter) {
		String result = request.getParameter(parameter);
		if (result == null || result.isEmpty()) {
			return null;
		}

		return result;
	}

	private String getParameterFromStringUrl(String url, String parameter) throws URISyntaxException {
		MultiValueMap<String, String> params = UriComponentsBuilder.fromUriString(url).build().getQueryParams();

		if (params.containsKey(parameter)) {
			return params.getFirst(parameter);
		}

		return null;
	}

	/**
	 * Which launch context the app asked for, read from the authorization server's action token.
	 * <p>
	 * The signature is not checked: the token is signed with the authorization server's key, which this
	 * module does not hold. Nothing security-relevant rests on the answer, which only decides whether
	 * the user is additionally asked to pick a visit.
	 */
	private String getLaunchTypeString(String key) {
		JWTClaimsSet claims = SmartLaunchTokens.readUnverifiedClaims(key);

		if (claims == null) {
			return null;
		}

		Object launchType = claims.getClaim("launchType");

		return launchType == null ? null : launchType.toString();
	}

}
