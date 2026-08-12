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

import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpStatus;
import org.openmrs.User;
import org.openmrs.api.context.Context;
import org.openmrs.module.smartonfhir.util.SmartLaunchContextService;
import org.openmrs.module.smartonfhir.web.util.FhirBaseAddressStrategy;

public class SmartEhrLaunchServlet extends HttpServlet {

	@Override
	protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
		final String patientId = req.getParameter("patientId");
		final String visitId = req.getParameter("visitId");
		final String launchContext = req.getParameter("launchContext");

		if (StringUtils.isBlank(launchContext)) {
			// Previously this was dereferenced without checking, so a missing parameter was a
			// NullPointerException rather than a bad request.
			resp.sendError(HttpStatus.SC_BAD_REQUEST, "A launchContext of 'patient' or 'encounter' must be provided");
			return;
		}

		final String contextId = "encounter".equals(launchContext) ? visitId : patientId;

		if (StringUtils.isBlank(contextId)) {
			resp.sendError(HttpStatus.SC_BAD_REQUEST,
			    "encounter".equals(launchContext) ? "A visitId must be provided" : "A patientId must be provided");
			return;
		}

		final User user = Context.getAuthenticatedUser();

		if (user == null) {
			resp.sendError(HttpStatus.SC_UNAUTHORIZED, "A launch must be started by an authenticated user");
			return;
		}

		final String baseUrl = new FhirBaseAddressStrategy().getBaseSmartLaunchAddress(req);

		if (StringUtils.isBlank(baseUrl)) {
			resp.sendError(HttpStatus.SC_BAD_REQUEST, "A launchUrl must be provided");
			return;
		}

		// The handle is opaque and single-use. It used to be the patient or visit uuid, which both
		// disclosed the context it stands for and let anyone holding a uuid forge a launch.
		final String launchHandle = new SmartLaunchContextService().issue(user.getUsername(), patientId, visitId);

		resp.sendRedirect(resp.encodeRedirectURL(baseUrl + launchHandle));
	}
}
