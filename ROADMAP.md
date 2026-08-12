# Roadmap

Outstanding work, in small independent chunks. Each one names what is wrong, what "done" means, and
where the code is, so it can be picked up without reading the rest of this file.

Sizes are rough: **S** under a day, **M** a few days, **L** a week or more.

Status against the specification is in [CONFORMANCE.md](CONFORMANCE.md); this is the list of things to
do about it.

---

## Blocking: correctness and safety

### 1. ~~The EHR-launch authenticator resolves the user as `admin`~~ — done

Fixed. It was not the privilege escalation it appeared to be: the app token's subject is the clinician,
verified against the shared secret, and the hardcoded name only went into Keycloak's internal envelope.
It was a hard failure — `NoResultException` on a stock OpenMRS database, where the admin user's
`username` is NULL and `admin` is its `system_id`.

The round trip now uses the execution's own action URL rather than a user-bound action token, and the
`launch` scope carries the context mappers it was missing. An EHR launch has been walked end to end and
is covered by `verify-env.sh`.

Still worth adding: a test that a launch handle issued to one user cannot be redeemed by another.
`SmartLaunchContextService` enforces it and `SmartLaunchContextServiceTest` covers the service, but
nothing covers it through the servlet.

### 2. ~~`smartEhrLaunchServlet` will redirect anywhere~~ — done

Fixed by 3. The launch address is looked up from the registry; a `launchUrl` in the request is ignored,
and `verify-env.sh` checks that a smuggled one does not change where the browser goes.
`SmartAppSelectorServlet`, which had the same flaw and no caller, is removed.

### 3. ~~No app registry~~ — done

Apps are recorded in `config/smart-apps.json`, read by `SmartAppRegistry`, and listed for the frontend
by `SmartAppsServlet` — without launch URLs or client ids, which a chart screen has no use for. An
unregistered app answers 404, and no registry file means nothing is launchable rather than a fallback
to a caller-supplied address.

Not done, and worth doing if apps ever need managing at runtime: there is no UI for editing the
registry, and no per-user or per-role restriction on which apps a given clinician may launch. Both were
out of scope for closing the open redirector.

### 4. ~~No way to start an EHR launch from O3~~ — done

"Launch an app" is in the patient banner's Actions menu, listing what the server has registered and
starting an EHR launch for the patient in the chart. Walked in a browser from sign-in to the app reading
that patient over FHIR. Hidden when no apps are registered.

Not done, and worth doing: the walk is a scratch script rather than a spec in the frontend module's
`e2e/`, so nothing guards it automatically. It needs the test app running, which is why it was left
out — worth solving, because this is now the flow a clinician actually uses.

### 4b. ~~A user whose OpenMRS username is NULL cannot sign in~~ — done

`system_id` is mapped, the user lookup and the credential query match either column, and the adapter
reports the system id when there is no username. Verified against a running Keycloak: `admin` obtains a
token, `doctor` still does, and an unknown username is refused as a bad credential rather than as a
server error.

Two things came out of it. The test schema declared `username NOT NULL`, which made the users this
concerns impossible to represent — that is why it went unnoticed for so long. And the fixture added to
cover it exposed a separate defect in the user search, whose optional criteria were combined with `or`:
any criterion left unsupplied made the whole query true, so a search by username returned every user.
Both fixed in `openmrs-contrib-keycloak-auth`.

### 5. `launch/encounter` redirects to a page that no longer exists — S

`SmartLaunchOptionSelected` sends an encounter launch to `findVisit.page`, deleted with the RefApp 2.x
UI. The scope is granted, so an app can ask for something that then 404s mid-launch.

**Done when** a visit-selection screen exists in the frontend module — the patient picker is the model,
including landing on a server endpoint rather than an SPA route — or, if that is deferred, the scope is
withdrawn from `scopes_supported` so nothing can ask for it.

---

## Conformance gaps

### 6. Granular scopes are not enforced — L

Scopes are parsed, granted and returned, but the resource server does not restrict requests by them.
The user's OpenMRS privileges are the real boundary, so a token scoped to one resource type can read
others. `permission-v2` is correctly not advertised.

**Done when** a request outside the granted scopes is refused, `permission-v2` is advertised, and there
are tests for each of read/search/create/update/delete against a scope that does and does not permit
it.

Enforcement belongs in the FHIR2 module's resource providers or an interceptor there, not in this
module — this module's job is to make the granted scopes available. Expect to coordinate with fhir2.

### 7. Introspection is advertised but unusable — S

`introspection_endpoint` is published, but a public client gets `403 Client not allowed` and bearer
authentication gets `401 invalid_client`. The spec expects a client authorized to introspect to be able
to authenticate with a SMART bearer token.

**Done when** either the endpoint is usable by a client configuration we actually ship — and the
required fields plus launch context are verified present — or it is removed from the discovery document
until it is. Do not leave it advertised and broken.

### 8. `online_access` is not implemented — S

Only `offline_access` exists, so an app cannot request a refresh token scoped to the user remaining
online. Define the scope in the realm and confirm what Keycloak issues for it.

### 9. Asymmetric client authentication is advertised but never exercised — S

`private_key_jwt` is in `token_endpoint_auth_methods_supported`. Keycloak supports it; this project has
never tested it. Either verify it with a registered JWKS and a signed assertion, or stop advertising
it.

### 10. CORS for browser-only apps is unverified — S

A purely browser-based app needs the discovery endpoint readable from any origin, and the token and
FHIR endpoints readable from its registered origin. The development realm is permissive; nothing checks
that a real browser app works.

---

## Separable: Backend Services

### 11. SMART Backend Services — L

The `client_credentials` profile for unattended clients: nightly exports, population analytics, lab
monitoring, bulk data. Needs `system/` scopes, `client-confidential-asymmetric` registration with a
JWKS, a one-time-use client assertion, and a resource-server path that authenticates a request with no
user behind it.

Nothing in the launch flows blocks this and it shares only the discovery document, so it can proceed
independently — but note that `system/` scopes are close to meaningless until **6** exists, because
there is no user whose privileges could stand in for them.

Worth splitting when picked up: (a) registration and the assertion, (b) the token endpoint,
(c) resource-server authentication with no user, (d) `system/` scope enforcement.

---

## Smaller improvements

### 12. The consent screen cannot name the app asking — S

The patient picker always says "An application is asking to open a patient record", because Keycloak
substitutes only `{TOKEN}` into the patient-selection URL. A consent screen that cannot say who is
asking is a weak consent screen.

**Done when** the authenticator substitutes a `{CLIENT_NAME}` placeholder and the picker shows it.

### 13. ~~`launch-ehr` and the EHR context capabilities are advertised without a walked flow~~ — earned

`launch-ehr` and `context-ehr-patient` are now honest: a clinician starts a launch from the chart and
the app is given that patient. `context-ehr-encounter` is still an overclaim, since encounter launch
depends on **5**.

### 14. Discovery endpoints are derived, Keycloak-shaped — S

`SmartConfigServlet` falls back to `/protocol/openid-connect/*` paths when the configuration does not
state an endpoint. Reading the authorization server's own discovery document instead would work for any
authorization server.

### 15. Keycloak's SMART SPIs are internal — ongoing

The authenticators, mapper and validator implement interfaces Keycloak logs `KC-SERVICES0047` about:
internal, may change without notice. A Keycloak upgrade can break the authorization server half with no
compiler warning. The realm and provider contract tests are the mitigation; keep them current and run
them first after any Keycloak bump.

### 16. Inferno conformance run — M

The flows have been verified with our own tests only. Running the
[Inferno](https://inferno-framework.github.io/) SMART App Launch suite would test them against
somebody else's reading of the specification, which is the point.

Expect it to fail on the items above, particularly scope enforcement and introspection. Worth doing
early anyway: the failures are the useful output.

---

## Suggested order

1–2 first: they are small, they are safety problems, and 1 blocks the whole EHR-launch line. Then 3 and
4 together, which is what makes EHR launch real and is the largest visible gap. 5 alongside them, since
it is the same frontend pattern.

7, 8, 9 and 13 are each a day or less and remove overclaims from the discovery document; do them
whenever, but before announcing conformance to anybody.

6 is the big one and the most valuable for a real deployment, because without it SMART scopes are
documentation rather than a boundary. 11 can start any time by someone else, and 16 should be run once
6 lands.
