<img src="https://repository-images.githubusercontent.com/232160993/36a03500-3221-11ea-9176-0786b70e4a13"  alt="OpenMRS on FHIR"/>

openmrs-module-smartonfhir
==========================
[![Build Status](https://github.com/openmrs/openmrs-module-smartonfhir/actions/workflows/main.yml/badge.svg)](https://github.com/openmrs/openmrs-module-smartonfhir/actions/workflows/main.yml)
[![codecov](https://codecov.io/gh/openmrs/openmrs-module-smartonfhir/branch/master/graph/badge.svg)](https://codecov.io/gh/openmrs/openmrs-module-smartonfhir)

Makes an OpenMRS server a [SMART App Launch](https://hl7.org/fhir/smart-app-launch/) **resource
server**. Third-party clinical apps authenticate against an external authorization server, are
granted access to one patient's record, and read it through the
[FHIR2 module](https://github.com/openmrs/openmrs-module-fhir2).

> **Branches.** `master` is the 1.x line, for the RefApp 2.x UI. `2.0.x` targets **O3 + Keycloak 26 +
> SMART App Launch 2.x** and is where current work happens. The badges above track `master`, so they
> say nothing about `2.0.x`.

## What this module does

Four things, and nothing else:

1. **Publishes the SMART discovery document** at `<fhir base>/.well-known/smart-configuration`, so an
   app can find the authorization server and see what this deployment supports.
2. **Verifies bearer tokens** on FHIR requests and turns a valid one into an OpenMRS session for the
   duration of that single request.
3. **Carries a launch through patient selection** — it accepts the authorization server's launch
   token, establishes a session from it, and hands the clinician's choice of patient back, signed.
4. **Records which apps may be launched** from a patient chart, and starts an EHR launch against one.

**It does not issue tokens and does not hold user credentials.** Anything here that looks like
authentication is either verifying somebody else's token or converting a token this server itself
signed into a session. It also reads and writes no clinical data — every FHIR resource comes from
FHIR2.

Nothing in it is specific to any one app. Any SMART client — a risk dashboard, a growth chart, a
reporting tool — gets the same launch, the same patient selection and the same FHIR access with no
change here. If you are writing one, the discovery document tells it everything it needs; on its own
side it has to send `aud`, use PKCE with `S256`, and read launch context from the token response
rather than from the access token's claims.

### Status on the `2.0.x` line

| | |
|---|---|
| Standalone launch with patient selection | Works. Walked end to end in a browser. |
| EHR launch | Works. The launch endpoint issues a handle and redirects to the app; the chart affordance that calls it belongs to the frontend, not here. |
| Bearer-token access to the FHIR API | Works. Tokens verified against the authorization server's published keys. |
| Discovery document | Served, with the fields SMART 2.x requires. |
| `launch/encounter` visit selection | Refused with `501`. The scope is granted upstream; there is no visit-selection step. |
| Granular (v2) scope enforcement | Not implemented. Scopes are parsed and advertised, never enforced — enforcement belongs in FHIR2's resource providers. |
| Backend Services (`client_credentials`) | Not implemented. |

## Requirements

| | | |
|---|---|---|
| OpenMRS platform | 2.8.8 | Tomcat 9, `javax.servlet` |
| Java runtime | 21 | Bytecode target is 17 (`maven.compiler.release`) |
| fhir2 | 4.2.0 | `provided`; serves every FHIR resource |
| authentication | 2.3.0 | `provided`; owns the bearer scheme's registration |
| An authorization server | — | Must implement the SMART App Launch extensions: `aud`, PKCE S256, and launch context in the token response. Keycloak 26 with the OpenMRS SMART authenticator plugin is what this is built and tested against. |

Bundled libraries are kept to a minimum, because every jar a module packages can collide with another
module's copy. **`nimbus-jose-jwt` (9.48) is the only third-party jar the omod ships** — verified with
`mvn dependency:list`. Token verification needs it and the platform does not provide it. Everything
else, Caffeine and Lombok included, is `provided`.

## Install

```bash
mvn clean install
cp omod/target/smartonfhir-2.0.0-SNAPSHOT.omod ~/openmrs/modules/
```

Requires JDK 17 or newer to build. Then configure it — an unconfigured module starts, logs one line,
and leaves every SMART endpoint dark.

## Configuration

Three JSON files under `<application data directory>/config`, and two runtime properties.

Every key below is spelled the way the code binds it. The config classes ignore unknown properties,
so **a misspelled key is discarded in silence** rather than reported. All of them are hyphenated, not
camelCase.

### 1. `config/smart-oauth2.json` — the authorization server

`issuer` and `audience` are both **required**. If either is missing the whole file is discarded, and
the discovery document is not served at all rather than served with guesses.

```json
{
  "issuer": "https://keycloak.example.org/realms/openmrs",
  "audience": "https://openmrs.example.org/openmrs/ws/fhir2/R4",
  "advertised-jwks-uri": "https://keycloak.example.org/realms/openmrs/protocol/openid-connect/certs",
  "allowed-clock-skew-seconds": 30
}
```

| key | meaning |
|---|---|
| `issuer` | The authorization server's issuer identifier. A token whose `iss` differs is rejected. |
| `audience` | This FHIR server's base URL, as an app names it in the SMART `aud` parameter. A token must carry it in `aud`. |
| `jwks-uri` | Where this server fetches signing keys. Defaults to the issuer's advertised `jwks_uri`. |
| `advertised-jwks-uri` | What apps are *told*, for when that differs from the above. |
| `username-claim` | The claim naming the OpenMRS user. Defaults to `preferred_username`. |
| `allowed-clock-skew-seconds` | Skew tolerated on `exp` and `nbf`. Defaults to 30. |
| `authorization-endpoint`, `token-endpoint`, `introspection-endpoint`, `revocation-endpoint`, `end-session-endpoint`, `registration-endpoint` | Optional. Derived from the issuer using OpenID Connect's conventional paths when absent. |

`advertised-jwks-uri` exists for the case where the authorization server has two names: this module
fetches keys server-to-server and may use a container-internal address, while an app reads the
discovery document from outside and needs one its browser can resolve. Spelling it `advertisedJwksUri`
produces exactly the unreachable-`jwks_uri` failure the field exists to prevent.

### 2. `config/smart-secret-key.json` — the launch signing secret

A base64 secret shared with the authorization server, used to sign the launch tokens the two exchange.
Without a usable key no launch can complete, and the module says so rather than proceeding.

```json
{
  "smart-shared-secret-key": "<base64, at least 256 bits>"
}
```

Generate one with:

```bash
openssl rand -base64 32
```

### 3. `config/smart-apps.json` — the launchable apps

Which apps may be launched from a chart, and where each one's launch is sent. An EHR launch names an
app by `id` and the address is looked up here; **an app absent from this file cannot be launched at
all.** That is the point — the launch URL used to come from a request parameter, which made the launch
endpoint an open redirector for single-use launch handles.

```json
{
  "apps": [
    {
      "id": "growth-chart",
      "name": "Growth Chart",
      "description": "Plots weight and height against WHO reference curves",
      "clientId": "growth-chart",
      "launchUrl": "https://growth.example.org/launch",
      "launchContext": "patient"
    }
  ]
}
```

`id` and `launchUrl` are required; an entry missing either is dropped, because it would otherwise
appear in a list of apps and then fail when chosen. `launchContext` is `patient` (the default) or
`encounter`, and a launch asking for something else is refused.

### 4. Register the bearer scheme

In `openmrs-runtime.properties`. Without this, every FHIR request carrying a SMART token answers `401`
with nothing to say why:

```properties
authentication.scheme=smartBearer
authentication.scheme.smartBearer.type=org.openmrs.module.smartonfhir.web.smart.SmartBearerTokenAuthenticationScheme
```

`smartBearer` is `SmartBearerCredentials.SCHEME_ID`; the two strings must match.

### Check it came up

```bash
curl -s https://openmrs.example.org/openmrs/ws/fhir2/R4/.well-known/smart-configuration | jq .
```

A `503` means the configuration was not read — check the log for the line written at startup. A `200`
should name your issuer, and its `capabilities` should list `launch-standalone`.

## Endpoints this module adds

| | |
|---|---|
| `GET <fhir base>/.well-known/smart-configuration` | The discovery document. Public. Also served directly at `/ms/smartConfig`. |
| `GET /ms/smartApps` | The apps a clinician may launch, as JSON. Requires a session. Deliberately omits each app's launch URL and client id. |
| `GET /ms/smartEhrLaunchServlet?appId=&patientId=[&visitId=]` | Starts an EHR launch. Requires a session. Redirects to the app's `launchUrl` with `iss` and `launch` appended. |
| `GET /ms/smartPatientSelection?token=` | Where the authorization server sends a launch that needs a patient chosen. Authenticates from the launch token, then redirects to the picker. |
| `GET /ms/smartLaunchOptionSelected?token=&patientId=` | Records the clinician's choice, signs it, and hands back to the authorization server. |
| `GET /ms/smartAccessConfirmation?token=` | The launch-access confirmation screen, ported from 1.x. |
| filter on `/ws/fhir2*` | Verifies a `Bearer` token and authenticates the request. Also the CORS headers the FHIR API needs. |

## How a launch works

```mermaid
sequenceDiagram
    participant App as SMART app
    participant KC as Authorization server
    participant M as This module
    participant P as Patient picker
    participant F as FHIR2

    App->>KC: /authorize (aud, PKCE S256, launch/patient)
    KC->>KC: does aud name this FHIR server?
    KC->>KC: clinician signs in
    KC->>M: 302 /ms/smartPatientSelection?token=...
    M->>M: bypass filter authenticates from the token
    M->>P: 302 to the picker (session now exists)
    P->>M: /ms/smartLaunchOptionSelected?token, patientId
    M->>M: sign the choice; end the session the launch created
    M->>KC: 302 action token
    KC->>App: 302 redirect_uri?code=...
    App->>KC: exchange code + PKCE verifier
    KC->>App: access token + patient in the token response
    App->>F: GET /ws/fhir2/R4/Patient/{id} (Bearer)
    F->>App: the record
```

An EHR launch skips the first half: the chart calls `/ms/smartEhrLaunchServlet` with the patient
already known, this module issues a launch handle and redirects to the app, and the app presents that
handle to the authorization server as its `launch` parameter.

Reading a patient with a token, once launched:

```bash
curl -H "Authorization: Bearer $ACCESS_TOKEN" \
     https://openmrs.example.org/openmrs/ws/fhir2/R4/Patient/$PATIENT_ID
```

The token is proof for that one request, not a login — the session it creates is discarded when the
request ends.

## How it is built, and why

**The launch lands on a servlet, never straight on a frontend route.**
`SmartPatientSelectionServlet` exists only to be redirected to. The O3 app shell renders **no** route
without a session — it redirects to the login page instead, discarding the launch token in the URL.
Pointing the authorization server at the picker's route therefore cannot work, even though it passes
when driven with `curl`. The servlet sits behind the bypass filter, so the token becomes a session
*before* anything is asked to render.

**Launch context is a random handle, not the patient's identifier.**
`SmartLaunchContextService` issues 256-bit URL-safe handles, single-use and bound to the clinician they
were issued to. An earlier design keyed the cache by patient UUID, so two launches for the same patient
collided. A refused redemption still consumes the handle, so a guessed handle cannot be probed against
different usernames.

**Bearer authentication does not survive the request.**
`SmartBearerTokenFilter` is mapped to the FHIR API alone and logs out in a `finally`. The filter it
replaces was mapped to every request in the webapp.

**Tokens are verified against published keys, never a shared secret.**
`SmartAccessTokenVerifier` permits asymmetric algorithms only, fetches JWKS through `JWKSourceBuilder`
(cached and rate-limited), requires `exp` rather than merely honouring it when present, and treats
`aud` as **membership** per RFC 7519 §4.1.3 — Keycloak issues multi-audience tokens, and exact string
equality rejected them.

**The authentication scheme is not a Spring component.**
`Context.setAuthenticationScheme()` resolves `getBean(AuthenticationScheme.class)`, which permits
exactly one bean. Registering ours as `@Component` silently disabled the authentication module with
`Multiple authentication schemes overrides`. `webModuleApplicationContext.xml` therefore excludes
`AuthenticationScheme` from the component scan, and the scheme is registered through the authentication
module's configuration instead.

**A launch does not leave a session behind.**
A standalone launch must sign the clinician in so they can search for a patient. That session used to
outlive the launch, leaving the browser holding a privileged session no visible logout would end — on a
shared workstation, the next person's session. `SmartLaunchOptionSelected` ends it at hand-off, but only
when the bypass filter created it, identified by a marker. A clinician already signed in keeps their own
session, which predates the launch and is not the launch's to end.

**Ending it does not invalidate the session container.**
`Context.logout()` and remove the marker; do **not** call `session.invalidate()`. Invalidating leaves
the browser holding a cookie for a session that no longer exists, and OpenMRS then answers `401` with
an HTML error page to the next request presenting it — including the session endpoint the frontend
polls, which expects `200` with `authenticated: false`.

**The discovery document is a contract, not a wish list.**
`SmartConfigServlet.CAPABILITIES` lists only what has been walked end to end. `launch-standalone` and
`context-standalone-patient` were absent until the flow completed in a browser; `permission-v2` is
still absent. An app that trusts a capability we do not implement fails in a way that looks like the
app's fault.

**`end_session_endpoint` is advertised.**
Without it an app has no discoverable way to log anyone out, and ending the OpenMRS session alone leaves
the authorization server's session intact — the next launch in that browser is granted **silently, as
whoever launched last**.

**Configuration is files, not global properties.**
Read once at startup through holders. The authorization server's identity is deployment configuration,
not something to edit in a running system's settings UI.

**Signature checks are skipped only where nothing rests on the answer.**
`SmartLaunchTokens.readUnverifiedClaims` exists because the authorization server's action token is
signed with a key this module does not hold. It reads `launchType`, which only decides whether to ask
for a visit. The nested token that establishes *who the user is* is verified against the shared secret.

**User lookup runs under a proxy privilege.**
`getUserByUsername` is `@Authorized("Get Users")`, so during authentication — before anyone is
authenticated — it threw and made a real user look nonexistent. The lookup is wrapped in
`addProxyPrivilege`/`removeProxyPrivilege` with the removal in a `finally`.

**The bypass filter keeps two lists that must agree.**
Its servlet mappings decide which requests it sees; its `validUrls` init-param decides which of those
may present a launch token. A URL in only one fails silently — mapped but not valid means the token is
refused, valid but not mapped means the filter never runs. `ModuleResourcesTest` asserts they agree, and
that the filter never sits in front of the session endpoint O3 logs in through.

**`javax.servlet`, not `jakarta.servlet`.** OpenMRS 2.8.x runs on Tomcat 9. Do not "modernise" this.

## Known gaps in this repo

Listed because pretending otherwise is worse.

- **Granular scopes are advertised but not enforced.** They are parsed off the token and handed to
  callers; nothing refuses a request that exceeds them. Enforcement belongs in FHIR2's resource
  providers.
- **`launch-ehr` and `context-ehr-encounter` are advertised on thinner evidence than
  `launch-standalone`.** The EHR launch endpoint has been walked; encounter context has not.
- **Discovery endpoints are derived Keycloak-shaped.** `SmartConfigServlet` falls back to
  `/protocol/openid-connect/*` when the configuration does not state them. Reading the issuer's own
  discovery document is the correct answer.
- **The launch token is decoded twice.** `SmartLaunchOptionSelected` calls `URLDecoder.decode` on a
  value the container already decoded. It is load-bearing — the `{APP_TOKEN}` placeholder only appears
  after the second pass — and preserved deliberately, because the chain is verified end to end around
  it.
- **The session-lifecycle logic has no unit test.** `Context` and `HttpSession` are static and
  container-owned; the honest coverage is at integration level. A unit test here would mostly assert
  that mocks were called.
- **The 1.x servlets were ported, not redesigned.** `SmartAccessConfirmation` and
  `SmartAppSelectorServlet` still carry 1.x assumptions.

## Testing

```bash
mvn clean install       # 90 tests: 81 in api, 9 in omod
```

`api` tests cover launch-token signing and verification, the access-token verifier's accept/reject
matrix (smuggled audiences, clock skew, missing `exp`, non-permitted algorithms), and launch-handle
entropy, single use and ownership.

`omod`'s `ModuleResourcesTest` covers what the compiler cannot: that every XML resource parses, that no
comment contains `--` (which XML forbids, and which once stopped this module and took all 31 modules of
RefApp 3.7.1 down with it, leaving REST answering 404), that only dependencies RefApp 3.7.1 ships are
required, and that the bypass filter's two URL lists agree.

Two habits worth keeping when adding tests here. **A test must be able to fail** — every guard in this
repository has been mutation-checked by reintroducing the defect and watching the test fail. Two
assertions written during this work were vacuous and only found that way. And **test through a path
that can see the failure**: the standalone launch once passed a full `curl` walk while being completely
broken in every browser, because `curl` never runs the app shell.

Write tests in given/when/then order, prefer `assertThat` with a Hamcrest matcher where it reads better
than an equality assertion, and name the test after the behaviour rather than the method — a failure
should be legible without opening the file.

## Contributing

Change only what the task requires; match the surrounding style even where you would write it
differently. Remove the imports and helpers *your* change orphaned, and leave unrelated dead code alone
— mention it instead. Comments earn their place by explaining why an obvious alternative is wrong, not
by restating the code.

Every source file carries the header from [license-header.txt](license-header.txt); add it to new files
with:

```bash
mvn com.mycila:license-maven-plugin:format
```

## License

[MPL 2.0 with Healthcare Disclaimer](LICENSE).
