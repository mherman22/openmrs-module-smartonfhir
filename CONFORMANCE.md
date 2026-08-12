# Conformance against SMART App Launch 2.2.0

Measured against [SMART App Launch 2.2.0 (STU 2.2)](https://hl7.org/fhir/smart-app-launch/), section
by section. Every "yes" below was exercised against a running server; the failures are recorded with
what the server actually answered, not with what it ought to answer.

The advertised `capabilities` array is the machine-readable version of this document. Where the two
could disagree, the rule is that a capability is claimed only after the flow behind it has been walked
end to end — the discovery document is a contract, and an app that trusts a capability we do not
implement fails in a way that looks like the app's fault.

## Apps: launch and authorization

| Spec requirement | Status | Notes |
|---|---|---|
| Register app (redirect URIs, launch URL) | **Manual** | No dynamic client registration. Redirect URIs at Keycloak; launch URLs in the module's `config/smart-apps.json`. |
| Standalone launch | **Yes** | Walked end to end in a browser, including patient selection. |
| EHR launch | **Yes** | Walked in a browser from a patient chart: "Launch an app" in the banner Actions menu, the app is notified with `iss` and `launch`, the handle is redeemed against the clinician's OpenMRS session with no password prompt, and the token response carries the patient. Apps are registered in `config/smart-apps.json`, so an unregistered app cannot be launched and the launch address cannot be supplied by the caller. |
| `.well-known/smart-configuration` | **Yes** | All SMART 2.x required fields present. |
| Authorization code flow | **Yes** | |
| PKCE, `S256` required, `plain` refused | **Yes** | `code_challenge_methods_supported` is `["S256"]` only. |
| `aud` required, validated | **Yes** | Enforced by a Keycloak authenticator before authentication. A launch omitting `aud`, or naming another server, is refused. Fails closed. |
| `state` round-tripped | **Yes** | Keycloak. Validating it is the app's responsibility. |
| Access token response carries launch context | **Yes** | `patient` is returned alongside `access_token`, per spec, not as a JWT claim. |
| `openid` + `fhirUser` produce an `id_token` | **Yes** | |
| Bearer token accepted on FHIR requests | **Yes** | Verified per request against the authorization server's published keys. Asymmetric algorithms only. |
| Refresh via `offline_access` | **Yes** | Tokens last 300s; refresh returns a new access token. |
| Refresh via `online_access` | **No** | Scope not defined. |
| `authorization_details` (experimental, multi-server) | **No** | Not implemented. |
| Public clients | **Yes** | |
| Confidential clients, symmetric (client secret) | **Yes** | |
| Confidential clients, asymmetric (`private_key_jwt`) | **Advertised, untested** | `token_endpoint_auth_methods_supported` includes `private_key_jwt`. Keycloak supports it; this project has never exercised it. Treat as unverified. |
| CORS on discovery and token endpoints | **Untested** | The realm sets web origins permissively for development. Not verified for a browser-only app. |

## Scopes and launch context

| Spec requirement | Status | Notes |
|---|---|---|
| `launch/patient` | **Yes** | Produces the patient-selection screen and returns `patient`. |
| `launch/encounter` | **No** | The visit-selection screen was removed with the RefApp 2.x UI; the servlet still redirects to a page that no longer exists. |
| `launch` (EHR context) | **Yes** | The scope carries both context mappers. It carried none for a while, so an EHR launch completed and returned no patient at all. |
| v2 granular scope **syntax** (`patient/Observation.rs`) | **Yes** | Parsed, granted, returned in `scope`. |
| v2 granular scope **enforcement** | **No** | The resource server does not restrict requests by scope; the user's OpenMRS privileges are the boundary. `permission-v2` is therefore not advertised. |
| `patient/*.rs`, `user/*.rs` wildcards | **No** | Expanding a wildcard is the authorization server's job and Keycloak does not do it: a scope must exist as a client scope to be requestable, and both forms answer `invalid_scope`. They were advertised in `scopes_supported` until that was measured; the advertisement has been removed rather than the truth adjusted. |
| `offline_access` | **Yes** | |
| `fhirUser` claim | **Yes** | |

## Backend Services

**Not implemented.** None of it: no `client_credentials` grant, no `system/` scopes, no
`client-confidential-asymmetric` registration, no JWKS-based client assertion.

This is the profile for unattended clients — nightly exports, population analytics, a lab monitoring
service, bulk data. It shares nothing with the launch flows above except the discovery document, so it
is separable work rather than an extension of what exists. `system/` scopes in particular are
meaningless until scope enforcement exists, since an unattended client has no user whose privileges
could stand in for them.

## Token introspection

| Spec requirement | Status | Notes |
|---|---|---|
| `introspection_endpoint` advertised | **Yes** | Keycloak's. |
| Usable by the app we ship | **No** | A public client gets `403 {"error":"invalid_request","error_description":"Client not allowed."}`. Keycloak requires a confidential client. |
| Authenticating to it with a SMART bearer token | **No** | `401 invalid_client`. The spec says any client authorized to introspect SHALL be able to authenticate this way; Keycloak does not support it. |
| Required fields (`active`, `scope`, `client_id`, `exp`) | **Untested** | Blocked on the above. Keycloak returns these for a confidential client. |
| Launch context (`patient`) in the introspection response | **Unknown** | The spec requires it when the token response carried it. Our context mapper writes to the token response; whether Keycloak mirrors it into introspection has not been checked. |

Introspection is advertised while being unusable for the client configuration we ship. That is the
same overclaim the capabilities list is careful to avoid, and it is an open item.

## Brands (User-access Brands and Endpoints)

**Not implemented.** No `Brand` or `Endpoint` resources are published. Relevant only for patient-facing
app directories.

## Where this document is enforced

- `SmartConfigServlet.CAPABILITIES` — the claimed capability list.
- `RealmDefinitionTest` — that every client inherits what the resource server requires, that the
  audience mapper is not tied to one client, and that no secret is committed.
- `verify-env.sh` in the distribution repository — 58 checks, including that no capability is
  advertised that is not implemented, and a complete standalone launch.
- `e2e/specs/standalone-launch.spec.ts` in the frontend module — the same launch in a real browser.
