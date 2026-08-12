/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.smartonfhir.web.smart;

import java.util.Properties;

import lombok.extern.slf4j.Slf4j;
import org.openmrs.User;
import org.openmrs.api.context.Authenticated;
import org.openmrs.api.context.AuthenticationScheme;
import org.openmrs.api.context.BasicAuthenticated;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.ContextAuthenticationException;
import org.openmrs.api.context.Credentials;
import org.openmrs.api.context.UsernamePasswordAuthenticationScheme;
import org.openmrs.module.authentication.AuthenticationCredentials;
import org.openmrs.module.authentication.UserLogin;
import org.openmrs.module.authentication.web.AuthenticationSession;
import org.openmrs.module.authentication.web.WebAuthenticationScheme;
import org.openmrs.module.smartonfhir.util.SmartAccessTokenVerifier;
import org.openmrs.module.smartonfhir.util.SmartAccessTokenVerifier.SmartAccessToken;
import org.openmrs.module.smartonfhir.util.SmartAccessTokenVerifierHolder;

/**
 * Authenticates FHIR requests that present a SMART access token, and stays out of the way
 * otherwise.
 * <p>
 * <strong>Why this shape.</strong> OpenMRS core permits exactly one {@code AuthenticationScheme}
 * Spring bean: {@code Context.setAuthenticationScheme()} calls
 * {@code getBean(AuthenticationScheme.class)} and, if more than one exists, logs <em>"Multiple
 * authentication schemes overrides are being provided"</em> and silently falls back to the platform
 * default, disabling every candidate. RefApp 3.7.1 already ships the authentication module, whose
 * {@code DelegatingAuthenticationScheme} is that bean. A second {@code @Component} here would
 * therefore disable both. So this class is <em>not</em> a Spring component: it is instantiated by
 * the authentication module from {@code authentication.scheme.{id}.type}, by reflection, and so
 * never competes for the bean.
 * <p>
 * <strong>Why O3 login is unaffected.</strong> The module's filter is mapped to {@code /*} and,
 * when the active scheme is a {@link WebAuthenticationScheme}, redirects unauthenticated requests
 * to {@link #getChallengeUrl}. Sending O3's app shell and REST calls to a login page would be a
 * serious regression, so this scheme returns no credentials and a <em>null</em> challenge URL for
 * any request without a bearer token; the filter treats a null challenge URL as "carry on" and
 * passes the request down the chain untouched. Non-bearer credentials are handed to the delegate,
 * which defaults to the platform's own username/password scheme — exactly what RefApp 3.7.1 uses
 * today.
 */
@Slf4j
public class SmartBearerTokenAuthenticationScheme extends WebAuthenticationScheme {

	public static final String AUTHORIZATION_HEADER = "Authorization";

	public static final String BEARER_PREFIX = "Bearer ";

	/**
	 * Scheme id to hand non-bearer credentials to. When unset the platform's username/password scheme
	 * is used, which is what RefApp 3.7.1 authenticates with, so installing this scheme changes nothing
	 * for users logging in normally.
	 */
	public static final String CONFIG_DELEGATE = "delegate";

	private String delegateSchemeId;

	private volatile AuthenticationScheme delegate;

	@Override
	public void configure(String schemeId, Properties config) {
		super.configure(schemeId, config);
		this.delegateSchemeId = config.getProperty(CONFIG_DELEGATE);
	}

	/**
	 * Credentials only for a request that actually presents a bearer token, and only if that token
	 * verifies. Returning null for everything else is what keeps the module's filter from redirecting
	 * ordinary traffic.
	 */
	@Override
	public AuthenticationCredentials getCredentials(AuthenticationSession session) {
		String bearerToken = bearerTokenFrom(session);

		if (bearerToken == null) {
			return delegateCredentials(session);
		}

		SmartAccessTokenVerifier verifier = SmartAccessTokenVerifierHolder.getVerifier();

		if (verifier == null) {
			log.error("A SMART access token was presented but SMART on FHIR is not configured; refusing it");
			return null;
		}

		SmartAccessToken token = verifier.verify(bearerToken);

		if (token == null) {
			// The verifier has already logged why. Returning null leaves the request
			// unauthenticated, and the FHIR layer answers 401.
			return null;
		}

		return new SmartBearerCredentials(getSchemeId(), token);
	}

	/**
	 * No interactive challenge for a bearer request: an API client cannot fill in a login form. Null
	 * for every other request too, so that the module's filter passes ordinary traffic through rather
	 * than redirecting it to a login page.
	 */
	@Override
	public String getChallengeUrl(AuthenticationSession session) {
		if (bearerTokenFrom(session) != null) {
			return null;
		}

		AuthenticationScheme resolved = resolveDelegate();

		if (resolved instanceof WebAuthenticationScheme) {
			return ((WebAuthenticationScheme) resolved).getChallengeUrl(session);
		}

		return null;
	}

	@Override
	protected Authenticated authenticate(AuthenticationCredentials credentials, UserLogin userLogin) {
		if (!(credentials instanceof SmartBearerCredentials)) {
			return delegateAuthenticate(credentials);
		}

		SmartBearerCredentials smartCredentials = (SmartBearerCredentials) credentials;
		User user = findUser(smartCredentials.getClientName());

		if (user == null) {
			// The token was valid, so the authorization server knows this person; OpenMRS does not.
			// Refusing is the only safe answer: there is no user whose privileges could apply.
			log.warn("A valid SMART access token named '{}', which is not an OpenMRS user",
			    smartCredentials.getClientName());
			throw new ContextAuthenticationException("Invalid credentials");
		}

		log.debug("Authenticated '{}' from a SMART access token", user.getUsername());

		return new BasicAuthenticated(user, smartCredentials.getAuthenticationScheme());
	}

	/**
	 * Also reachable through {@code Context.authenticate(Credentials)}, which bypasses
	 * {@link #getCredentials}. Bearer credentials cannot be forged into existence here, because
	 * {@link SmartBearerCredentials} can only be built from an already-verified token.
	 */
	@Override
	public Authenticated authenticate(Credentials credentials) throws ContextAuthenticationException {
		if (credentials instanceof SmartBearerCredentials) {
			return super.authenticate(credentials);
		}

		return delegateAuthenticate(credentials);
	}

	@Override
	public boolean isUserConfigurationRequired(User user) {
		// Nothing for a user to set up: the authorization server owns the credential.
		return false;
	}

	private String bearerTokenFrom(AuthenticationSession session) {
		if (session == null) {
			return null;
		}

		String header = session.getRequestHeader(AUTHORIZATION_HEADER);

		if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
			return null;
		}

		String token = header.substring(BEARER_PREFIX.length()).trim();

		return token.isEmpty() ? null : token;
	}

	private User findUser(String username) {
		try {
			return Context.getUserService().getUserByUsername(username);
		}
		catch (Exception e) {
			log.error("Could not look up the OpenMRS user named '{}'", username, e);
			return null;
		}
	}

	/**
	 * The scheme that handles everything this one does not. Defaults to the platform's
	 * username/password scheme, so behaviour for ordinary logins is unchanged from a deployment with no
	 * scheme configured.
	 */
	private AuthenticationScheme resolveDelegate() {
		AuthenticationScheme resolved = delegate;

		if (resolved == null) {
			synchronized (this) {
				if (delegate == null) {
					delegate = buildDelegate();
				}
				resolved = delegate;
			}
		}

		return resolved;
	}

	private AuthenticationScheme buildDelegate() {
		if (delegateSchemeId != null && !delegateSchemeId.trim().isEmpty()) {
			try {
				return org.openmrs.module.authentication.AuthenticationConfig
				        .getAuthenticationScheme(delegateSchemeId.trim());
			}
			catch (Exception e) {
				log.error("Could not load the configured delegate scheme '{}'; falling back to username/password",
				    delegateSchemeId, e);
			}
		}

		return new UsernamePasswordAuthenticationScheme();
	}

	private AuthenticationCredentials delegateCredentials(AuthenticationSession session) {
		AuthenticationScheme resolved = resolveDelegate();

		if (resolved instanceof WebAuthenticationScheme) {
			return ((WebAuthenticationScheme) resolved).getCredentials(session);
		}

		// A non-web delegate has no notion of reading credentials from a request. Returning null with a
		// null challenge URL leaves the request to the rest of the chain, which is how OpenMRS behaves
		// with no scheme configured at all.
		return null;
	}

	private Authenticated delegateAuthenticate(Credentials credentials) {
		return resolveDelegate().authenticate(credentials);
	}
}
