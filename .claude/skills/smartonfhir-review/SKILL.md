---
name: smartonfhir-review
description: >-
  Repo-specific addendum to the pr-review skill for openmrs-module-smartonfhir.
  Adds what this module's history has already proved about where its bugs live,
  which claims cannot be settled by reading the diff, and which of its apparent
  defects are deliberate. Appended after the pr-review methodology, never
  instead of it.
---

# Reviewing openmrs-module-smartonfhir

This is an addendum. Everything in the `pr-review` methodology still governs — the verification bar, the inline-comment rules, the ban on posting a clean "LGTM". What follows is the context that methodology cannot carry, because it is specific to this module and was learned by breaking it.

## What this module is, so you review the right thing

It is a SMART App Launch **resource server** and nothing more. Four responsibilities: publish the discovery document, verify bearer tokens on FHIR requests, carry a launch through patient selection, and record which apps may be launched. It issues no tokens, holds no credentials, and reads no clinical data.

So these are not findings against this repo, and asking for them here is noise:

- **Granular (v2) scope enforcement.** Scopes are parsed and advertised; enforcement belongs in fhir2's resource providers, where the resources actually are.
- **The patient-picker UI.** A frontend route. This module only redirects to it.
- **Token issuance, PKCE checking, `aud` validation at `/authorize`.** The authorization server's job.

## Where this module's bugs actually live

The launch is a chain across three servers, and every expensive bug found in it so far has been at a boundary rather than inside a method.

**A launch that passes `curl` can be broken in every browser.** This has happened: the standalone launch completed a full `curl` walk while failing 100% of the time in a real browser, because the O3 app shell renders no route without a session and redirected to the login page, discarding the launch token from the URL. `curl` never runs the app shell. So for any change touching redirects, session establishment, or the picker hand-off, a passing test suite is not evidence — ask whether the path was walked in a browser, and treat "the tests pass" as silent on it.

**Session lifecycle is the second source.** A standalone launch must sign the clinician in, and that session must not outlive the launch — otherwise the browser is left holding a privileged session no visible logout ends, which on a shared workstation becomes the next person's session. Two specific traps, both of which have bitten:

- The teardown must be `Context.logout()` plus removing the bypass marker, **never `session.invalidate()`**. Invalidating leaves the browser with a cookie for a session that no longer exists, and OpenMRS then answers `401` with an HTML error page to the next request presenting it — including the session endpoint the frontend polls, which expects `200` with `authenticated: false`.
- The session may only be ended when the bypass filter created it, identified by the marker it leaves. A clinician already signed in keeps their own session; it predates the launch and is not the launch's to end.

**Token verification is the third.** Two different token types with two different key regimes, and conflating them is the dangerous mistake:

- **Access tokens** (presented on FHIR requests) are verified against the authorization server's *published* keys, asymmetric algorithms only. Adding any HMAC algorithm to the permitted set would mean accepting a token signed with a secret this module also holds. `exp` must be *required*, not merely honoured when present. `aud` is checked as **membership** per RFC 7519 §4.1.3, because Keycloak issues multi-audience tokens and exact equality rejected them.
- **Launch tokens** (exchanged with the authorization server) are HS256 against the shared secret, by design. A comment claiming this module "verifies everything against JWKS" is wrong, and so is one claiming it "signs with a shared secret" without saying which token.

## Claims here that a diff cannot settle

Ask for evidence, or ship the finding as a question, when a claim depends on:

- **Filter ordering or servlet mapping.** Whether a filter sees a request is decided by `config.xml`, not by the filter's code. `AuthenticationByPassFilter` keeps two lists that must agree — its `<filter-mapping>` URL patterns and its `validUrls` init-param. A URL in only one fails *silently*: mapped but not valid means the token is refused; valid but not mapped means the filter never runs. `ModuleResourcesTest` asserts they agree — if a PR adds a launch path to one list only, that test is the thing to point at.
- **Anything visible only in a browser.** Redirect chains, cookie behaviour, the app shell.
- **Anything about the authorization server's behaviour.** Which scopes it grants, which protocol mappers exist, whether an action token needs a user id. Read the realm configuration or the Keycloak provider; do not infer it from this module's expectations of it.

## Deliberate decisions that look like defects

Check the reasoning before flagging these; each is documented in the code or the README, and each has been raised and answered before.

- **`javax.servlet`, not `jakarta.servlet`.** The platform runs on Tomcat 9. This is not an outdated import to modernise, and a PR "fixing" it is the finding.
- **The launch token is decoded twice.** `SmartLaunchOptionSelected` calls `URLDecoder.decode` on a value the container already decoded. It is load-bearing — the `{APP_TOKEN}` placeholder only appears after the second pass.
- **`readUnverifiedClaims` skips a signature check.** Only for `launchType`, which decides whether to ask for a visit. The nested token establishing *who the user is* is verified.
- **The authentication scheme is not a `@Component`.** `Context.setAuthenticationScheme()` resolves `getBean(AuthenticationScheme.class)`, which permits exactly one bean; registering it as a component silently disables the authentication module. The component scan excludes it on purpose.
- **User lookup runs under a proxy privilege.** `getUserByUsername` is `@Authorized("Get Users")`, and during authentication nobody is authenticated yet, so without the proxy privilege a real user looks nonexistent. The removal must be in a `finally`.
- **`identify(user)` falls back to `getSystemId()`.** The stock OpenMRS admin has a **NULL** username, so username-only identification breaks the admin account outright.

## Things to check that this repo has been burned by

- **A new third-party jar in `omod`.** Every jar the omod packages can collide with another module's copy. `nimbus-jose-jwt` is the only one, deliberately; everything else is `provided`. A new compile-scope dependency in `omod/pom.xml` is a finding until justified.
- **`--` inside an XML comment.** XML forbids it. It once stopped this module and took all 31 modules of RefApp 3.7.1 down with it, leaving REST answering 404. `ModuleResourcesTest` guards it.
- **A capability added to `SmartConfigServlet.CAPABILITIES`.** The discovery document is a contract. A capability is earned by walking the flow end to end, not by the mechanism existing — an app that trusts one we don't implement fails in a way that looks like the app's fault. Adding `permission-v2` while scopes go unenforced is the concrete example.
- **A config key spelled in camelCase.** The config classes are `@JsonIgnoreProperties(ignoreUnknown = true)`, so a misspelled key is discarded in silence. `advertisedJwksUri` instead of `advertised-jwks-uri` produces exactly the unreachable-`jwks_uri` failure the field exists to prevent — and it produces it at runtime, in a deployment, with no error anywhere.
- **A launch handle that is not single-use or not owner-bound.** Redemption must consume the handle *even when refused*, or a guessed handle can be probed against different usernames.

## The test bar in this repo

Every guard here has been mutation-checked: reintroduce the defect, watch the test fail, restore, watch it pass. Two consequences for review.

**A test that cannot fail is a finding, not coverage.** Two assertions written for this module were vacuous — they queried a different cookie jar than the one under test and passed with the fix fully reverted. If a PR adds a test for a guard, the question is whether it fails without the guard, and the answer is not derivable from reading it.

**A whitelist needs a positive control.** The algorithm whitelist had a surviving mutation for a while: adding `HS256` to the permitted set broke nothing, because the test JWKS held only RSA keys, so no HMAC-signed token could ever be verified anyway. It became load-bearing only once the test published an `OctetSequenceKey` alongside. Any "we reject X" test needs a case where X would otherwise succeed.

## Voice

Same as the base skill, with one local note: commits here are single-line and deliberately terse, with no ticket prefix. Don't ask for JIRA keys in commit messages or PR titles.
