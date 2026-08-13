<img src="https://repository-images.githubusercontent.com/232160993/36a03500-3221-11ea-9176-0786b70e4a13"  alt="OpenMRS on FHIR"/>

openmrs-module-smartonfhir
==========================
[![Build Status](https://github.com/openmrs/openmrs-module-smartonfhir/actions/workflows/main.yml/badge.svg)](https://github.com/openmrs/openmrs-module-smartonfhir/actions/workflows/main.yml)
[![codecov](https://codecov.io/gh/openmrs/openmrs-module-smartonfhir/branch/master/graph/badge.svg)](https://codecov.io/gh/openmrs/openmrs-module-smartonfhir)

Makes an OpenMRS server a [SMART App Launch](https://hl7.org/fhir/smart-app-launch/) **resource
server**: third-party clinical apps authenticate against an external authorization server, are
granted a patient's record, and read it through the
[FHIR2 module](https://github.com/openmrs/openmrs-module-fhir2).

> **Branches.** `master` is the 1.x line, for the RefApp 2.x UI. `2.0.x` targets **O3 + Keycloak 26 +
> SMART App Launch 2.x** and is where current work happens. The badges above track `master`, so they
> say nothing about `2.0.x`.

## What this module does, and what it does not

The division of responsibility is the single most important thing to understand here.

| | |
|---|---|
| **This module** | Publishes the SMART discovery document. Verifies bearer tokens on FHIR requests. Carries a launch through the point where a clinician chooses a patient. Signs that choice back to the authorization server. |
| **The authorization server** (Keycloak, plus [openmrs-contrib-keycloak-smart-auth](https://github.com/openmrs/openmrs-contrib-keycloak-smart-auth)) | Authenticates the clinician, issues tokens, holds SMART launch state, and puts launch context into the token response. |
| **The frontend module** ([openmrs-esm-smart-app-launch-app](https://github.com/mherman22/openmrs-esm-smart-app-launch-app)) | The patient-selection screen a clinician sees mid-launch. |
| **The FHIR2 module** | Every FHIR resource. This module never reads or writes clinical data. |

**This module does not issue tokens and does not hold user credentials.** Anything that looks like
authentication here is either verifying somebody else's token or converting a token this server
signed into an OpenMRS session.

**Nothing here is specific to the app that ships with it.** Any SMART client — a risk dashboard, a
growth chart, a reporting tool — registers against the same realm and gets the same launch, the same
patient-selection screen and the same FHIR access, with no change to this module. If you are building
one, start with **[INTEGRATING.md](INTEGRATING.md)**.

### Status

| | |
|---|---|
| Standalone launch with patient selection | Works. Walked end to end in a browser. |
| Bearer-token access to the FHIR API | Works. Tokens verified against the authorization server's published keys. |
| Discovery document | Served, with SMART 2.x required fields. |
| Clinicians authenticate with their OpenMRS password | Works, via Keycloak user federation. No second user directory. |
| EHR launch from O3 | **Not usable.** Nothing links to `SmartEhrLaunchServlet`; there is no app registry and no chart affordance. |
| `launch/encounter` visit selection | **Broken.** `SmartLaunchOptionSelected` still redirects to `findVisit.page`, deleted with the RefApp 2.x UI. |
| Granular (v2) scope enforcement | **Not implemented.** Scopes are parsed and advertised, never enforced. |

## Technology stack

Versions are the ones actually built against, from `pom.xml`:

| | | |
|---|---|---|
| OpenMRS platform | 2.8.8 | The platform under RefApp 3.7.1 |
| Java bytecode target | 17 | `maven.compiler.release` |
| Java runtime | 21 | RefApp 3.7.1 ships Corretto 21 |
| fhir2 | 4.2.0 | `provided` |
| authentication | 2.3.0 | `provided` |
| nimbus-jose-jwt | 9.48 | JWT verification |
| Caffeine | 2.8.2 | Launch-context cache; already on the platform |
| Lombok | 1.18.36 | `provided` |
| javax.servlet-api | 4.0.1 | **not** `jakarta` — see below |
| JUnit | 5.11.4 | Jupiter, with `@Nested` and `@ParameterizedTest` |
| Mockito | 5.14.2 | Replaced PowerMock |

Two constraints shape this list.

**Bundled libraries are a liability.** Every jar this module packages can collide with another
module's copy. `nimbus-jose-jwt` is the *only* third-party jar the omod ships — verified with
`mvn dependency:list`, which reports nothing else at compile scope beyond this module's own `api`
jar. Token verification needs it and the platform does not provide it. Everything else, Caffeine and
Lombok included, is `provided`.

**`javax.servlet`, not `jakarta.servlet`.** OpenMRS 2.8.x runs on Tomcat 9. The Keycloak provider in
the sibling repository uses `jakarta.*` because Keycloak 26 requires it. The two live in different
runtimes and must not be made to match.

## Architecture

### The launch, end to end

```mermaid
sequenceDiagram
    participant App as SMART app
    participant KC as Keycloak
    participant M as This module
    participant SPA as Patient picker (ESM)
    participant F as FHIR2

    App->>KC: /authorize (aud, PKCE S256, launch/patient)
    KC->>KC: audience validator: does aud name this FHIR server?
    KC->>KC: clinician signs in with their OpenMRS password
    KC->>M: 302 /ms/smartPatientSelection?token=...
    M->>M: bypass filter authenticates from the token
    M->>SPA: 302 /spa/smart/select-patient (session now exists)
    SPA->>M: /ms/smartLaunchOptionSelected?token, patientId
    M->>M: sign the choice; end the session the launch created
    M->>KC: 302 action token
    KC->>App: 302 redirect_uri?code=...
    App->>KC: exchange code + PKCE verifier
    KC->>App: access token + patient in the token response
    App->>F: GET /ws/fhir2/R4/Patient/{id} (Bearer)
    F->>App: the record
```

### Decisions, and why

**The launch lands on a servlet, never on a frontend route.**
`SmartPatientSelectionServlet` exists only to be redirected to. The picker itself is a frontend
module, and the O3 app shell renders **no** route without a session — it redirects to the login page
instead, discarding the launch token in the URL. Pointing the authorization server straight at
`/spa/smart/select-patient` therefore cannot work, even though it passes when driven with `curl`. The
servlet is behind the bypass filter, so the token becomes a session *before* the frontend is asked to
render anything.

**Launch context is a random handle, not the patient's identifier.**
`SmartLaunchContextService` issues 256-bit URL-safe handles that are single-use and bound to the
clinician they were issued to. An earlier design keyed the cache by patient UUID, so two launches for
the same patient collided. A refused redemption still consumes the handle, so a guessed handle cannot
be probed against different usernames.

**Bearer authentication does not survive the request.**
`SmartBearerTokenFilter` is mapped to `/ws/fhir2*` alone and calls `Context.logout()` in a `finally`.
The filter it replaces was mapped to every request in the webapp. A token is proof for one request,
not a login.

**The authentication scheme is not a Spring component.**
`Context.setAuthenticationScheme()` resolves `getBean(AuthenticationScheme.class)`, which permits
exactly one bean. Registering ours as `@Component` silently disabled the authentication module with
`Multiple authentication schemes overrides`. `webModuleApplicationContext.xml` therefore excludes
`AuthenticationScheme` from the component scan, and the scheme is registered through the
authentication module's own configuration instead.

**Tokens are verified against published keys, never a shared secret.**
`SmartAccessTokenVerifier` allows asymmetric algorithms only, fetches JWKS through
`JWKSourceBuilder` (cached and rate-limited), and treats `aud` as **membership**, per RFC 7519
§4.1.3 — Keycloak issues multi-audience tokens, and exact string equality rejected them.

**`jwks_uri` is advertised separately from where keys are fetched.**
`SmartOAuth2Config.advertisedJwksUri` exists because the URL this server uses to reach Keycloak
(inside a container network) is not a URL the app's browser can resolve.

**Non-web classes live in `api`.**
14 classes in `api` (tokens, verification, configuration, launch context, credentials); 12 in `omod`
(4 filters, 6 servlets, the authentication scheme, the FHIR base-address strategy). Anything with no
servlet dependency belongs in `api`, where it can be tested without a web context and reused by other
modules.

**The discovery document is a contract, not a wish list.**
`SmartConfigServlet.CAPABILITIES` lists only what has been walked. `launch-standalone` and
`context-standalone-patient` were absent until the flow completed in a browser. `permission-v2` is
still absent, because granular scopes are parsed but not enforced — and enforcement belongs in the
FHIR2 resource providers, not here. An app that trusts a capability we do not implement fails in a way
that looks like the app's fault.

**A launch does not leave a session behind.**
A standalone launch has to sign the clinician in so they can search for a patient. That session used
to outlive the launch, leaving the browser holding a fully privileged session that no visible logout
would end — on a shared workstation, the next person's session. `SmartLaunchOptionSelected` now ends
it at hand-off, but only when the bypass filter created it (identified by the marker it leaves). A
clinician already signed in to OpenMRS keeps their own session, which predates the launch and is not
the launch's to end.

**The session container is left intact when ending that session.**
`Context.logout()` and remove the marker; do **not** call `session.invalidate()`. Invalidating leaves
the browser holding a cookie for a session that no longer exists, and OpenMRS then answers `401` with
an HTML error page to the next request presenting it — including the session endpoint the frontend
polls, which expects `200` with `authenticated: false`.

**`end_session_endpoint` is advertised.**
Without it an app has no discoverable way to log anyone out. Ending the OpenMRS session alone leaves
the authorization server's session intact, and the next launch in that browser is granted **silently,
as whoever launched last**.

**Configuration is files, not global properties.**
`smart-oauth2.json` and `smart-secret-key.json` under the application data directory, read once at
startup through holders. The authorization server's identity is deployment configuration, not
something to edit in a running system's settings UI.

**Signature checks are skipped only where nothing rests on the answer.**
`SmartLaunchTokens.readUnverifiedClaims` exists because the authorization server's action token is
signed with a key this module does not hold. It is used to read `launchType`, which only decides
whether to ask for a visit. The nested token that establishes *who the user is* is verified against
the shared secret.

**User lookup runs under a proxy privilege.**
`Context.getUserService().getUserByUsername` is `@Authorized("Get Users")`, so during authentication —
before anyone is authenticated — it threw and made a real user look nonexistent. The lookup is wrapped
in `addProxyPrivilege`/`removeProxyPrivilege` with the removal in a `finally`.

**The bypass filter keeps two lists that must agree.**
Its servlet mappings decide which requests it sees; its `validUrls` init-param decides which of those
may present a launch token. A URL in only one fails silently — mapped but not valid means the token is
refused, valid but not mapped means the filter never runs. `ModuleResourcesTest` asserts they agree,
and that this filter never sits in front of the session endpoint O3 logs in through.

## Development principles

**Verify against the running system, not against reasoning.** Every claim in this README that says
something "works" was walked against a live stack. Reading the code is how you form a hypothesis, not
how you confirm one.

**Test through a path that can see the failure.** The standalone launch passed a full `curl` walk
while being completely broken in every browser, because `curl` never runs the app shell. If a bug
class is invisible to your harness, the harness is not evidence.

**A test must be able to fail.** Every guard in this repository has been mutation-checked:
reintroduce the defect, watch the test fail, restore, watch it pass. Two assertions written during
this work were **vacuous** and only discovered that way — they queried a different cookie jar than the
one under test, so they reported success no matter what.

**Comments record the failure, not the mechanism.** The code says what it does. A comment earns its
place by explaining why an obvious alternative is wrong, and the ones here name the incident: which
XML character took 31 modules down, which redirect discarded a launch token.

**Fail closed.** A launch that never names its audience is refused, not admitted. A token that cannot
be verified is refused. Absent configuration means the discovery document is not served at all rather
than served with guesses.

**Change only what the task requires.** Match the surrounding style even where you would write it
differently. Remove the imports and helpers *your* change orphaned; leave unrelated dead code alone
and mention it.

**Prefer deleting to configuring.** The frontend's custom launch-session hook disappeared once the
servlet established the session first. Roughly 40 lines and a whole class of state went with it.

## Where we knowingly break these principles

Listed because pretending otherwise is worse.

**We depend on Keycloak's internal SPIs.** The SMART authenticator, mapper and validator implement
interfaces Keycloak logs `KC-SERVICES0047` about: internal, may change without notice. There is no
public SPI for this. A Keycloak upgrade can break the authorization server half without any warning
from the compiler, so the realm and provider have their own contract tests.

**The authorization server's `SmartLaunchAccessAuthenticator` hardcodes `admin` as the subject.** In
the sibling Keycloak repository rather than here, but it gates a flow this module serves. A deliberate
deferral carried from 1.x; it affects the launch-access flow, not the standalone launch, and must not
ship to a real deployment.

**`launch-ehr`, `context-ehr-patient` and `context-ehr-encounter` are advertised without a walked UI
flow.** The server-side mechanism exists; nothing in O3 initiates it. By the standard applied to
`launch-standalone`, these are overclaims and should be removed or earned.

**Discovery endpoints are derived from the issuer, Keycloak-shaped.** `SmartConfigServlet` falls back
to `/protocol/openid-connect/*` paths when the configuration does not state them. Reading the issuer's
own discovery document is the correct answer.

**The launch token is decoded twice.** `SmartLaunchOptionSelected` calls `URLDecoder.decode` on a
value the servlet container already decoded. It is load-bearing — the `{APP_TOKEN}` placeholder only
appears after the second pass — and preserved deliberately rather than corrected, because the whole
chain is verified end to end around it.

**The picker cannot name the app asking.** It always falls back to "An application", because Keycloak
substitutes only `{TOKEN}` into the patient-selection URL. A consent screen that cannot say who is
asking is a weak consent screen; the fix is a `{CLIENT_NAME}` placeholder in the authenticator.

**The session-lifecycle logic has no unit test.** `Context` and `HttpSession` are static and
container-owned, and the honest coverage is at integration level: `verify-env.sh` and the frontend's
Playwright spec, both mutation-checked. A unit test here would mostly assert that mocks were called.

**The 1.x servlets were ported, not redesigned.** `SmartAccessConfirmation`,
`SmartAppSelectorServlet` and `SmartEhrLaunchServlet` still carry 1.x assumptions, including a
redirect to a page that no longer exists.

## Testing

```bash
mvn clean install       # 74 tests: 65 in api, 9 in omod
```

`api` tests cover token signing and verification, the access-token verifier's accept/reject matrix
(including smuggled audiences and clock skew), and launch-handle entropy, single use and ownership.

`omod`'s `ModuleResourcesTest` covers what the compiler cannot: that every XML resource parses, that
no comment contains `--` (which XML forbids, and which once stopped this module and took all 31
modules of RefApp 3.7.1 down with it, leaving REST answering 404), that only dependencies RefApp
3.7.1 ships are required, and that the bypass filter's two URL lists agree.

Behaviour that only exists across servers is tested where it lives:

| | |
|---|---|
| [openmrs-distro-smartonfhir](https://github.com/mherman22/openmrs-distro-smartonfhir) | `./verify-env.sh` — 58 checks, including a whole standalone launch driven with `curl` |
| [openmrs-esm-smart-app-launch-app](https://github.com/mherman22/openmrs-esm-smart-app-launch-app) | `yarn test:e2e` — the same launch in a real browser, plus session lifecycle |
| Same repo | `./smart-test-app.py` — a minimal SMART app for launching by hand |

Write tests in given/when/then order, and prefer `assertThat` with a Hamcrest matcher where it reads
better than an equality assertion. Name the test after the behaviour, not the method: a failure should
be legible without opening the file.

## Building

Requires JDK 17 or newer to build; RefApp 3.7.1 runs it on 21.

```bash
mvn clean install
# omod/target/smartonfhir-2.0.0-SNAPSHOT.omod
```

## Configuration

Three files under the OpenMRS application data directory, and two runtime properties. Every key below
is spelled as the code binds it: the config classes ignore unknown properties, so a misspelling is
discarded in silence rather than reported.

`config/smart-oauth2.json` — the authorization server. **`issuer` and `audience` are both required**;
the file is discarded entirely when either is missing, which leaves one line in the log at startup and
every SMART endpoint dark. Endpoints are derived from the issuer when absent. All keys are
hyphenated, not camelCase:

```json
{
  "issuer": "https://keycloak.example.org/realms/openmrs",
  "audience": "https://openmrs.example.org/openmrs/ws/fhir2/R4",
  "advertised-jwks-uri": "https://keycloak.example.org/realms/openmrs/protocol/openid-connect/certs",
  "allowed-clock-skew-seconds": 30
}
```

`advertised-jwks-uri` is what apps are told, for when that differs from where this server fetches keys
— a container-internal hostname reaches Keycloak but not a browser. Spelling it `advertisedJwksUri`
produces exactly the unreachable-`jwks_uri` failure the field exists to prevent.

`config/smart-secret-key.json` — the secret shared with the authorization server, used to sign the
launch tokens the two exchange. Without a usable key no launch can complete, and the module says so
rather than proceeding.

`config/smart-apps.json` — the apps that may be launched from a patient chart, and where each one's
launch is sent. An EHR launch names an app by id; an app absent from this file cannot be launched at
all.

```json
{
  "apps": [
    {
      "id": "growth-chart",
      "name": "Growth Chart",
      "clientId": "growth-chart",
      "launchUrl": "https://growth.example.org/launch",
      "launchContext": "patient"
    }
  ]
}
```

**Register the bearer scheme** in `openmrs-runtime.properties`, or every FHIR request carrying a SMART
token answers 401 with nothing to say why:

```properties
authentication.scheme=smartBearer
authentication.scheme.smartBearer.type=org.openmrs.module.smartonfhir.web.smart.SmartBearerTokenAuthenticationScheme
```

`smartBearer` is `SmartBearerCredentials.SCHEME_ID`; the two must match.

The [distribution repository](https://github.com/mherman22/openmrs-distro-smartonfhir) writes all three
files and both properties, and brings up a working stack.

## License

[MPL 2.0 with Healthcare Disclaimer](LICENSE). Every source file carries the header from
[license-header.txt](license-header.txt); add it to new files with:

```bash
mvn com.mycila:license-maven-plugin:format
```
