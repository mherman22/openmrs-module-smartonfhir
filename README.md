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

## How OpenMRS and Keycloak fit together

Neither server calls the other. Every exchange between them travels as a browser redirect, and the
trust between them rests on two separate mechanisms pointing in opposite directions:

| | Held by OpenMRS | Held by the authorization server | Used for |
|---|---|---|---|
| **A shared HMAC secret** | `config/smart-secret-key.json` | the authenticator's config in the realm | OpenMRS asserting *who the clinician is* during a launch |
| **Published RSA keys (JWKS)** | fetched, never held | the private half | this module verifying access tokens on FHIR calls |

The secret is the interesting one: it is an **authentication credential**, not a checksum. Anything
that can sign with it can assert any username to the authorization server without a password. That is
why the module refuses keys shorter than 256 bits, and why launch tokens live five minutes.

## The EHR launch, on the wire

A clinician looking at a chart clicks an app and lands in it, already on that patient, without typing
a password. Six hops, captured from a running stack (values redacted, shape intact):

```http
302 /openmrs/ms/smartEhrLaunchServlet?appId=test-app&patientId=c3ab5d9b-…
302 http://localhost:3000/?iss=…%2Fws%2Ffhir2%2FR4&launch=<handle>
302 …/realms/openmrs/protocol/openid-connect/auth?client_id=smartClient&response_type=code
    &scope=openid+launch+fhirUser+patient%2FPatient.rs&aud=…%2Fws%2Ffhir2%2FR4
    &code_challenge=<challenge>&code_challenge_method=S256&launch=<handle>
302 /openmrs/smartonfhir/smartAccessConfirmation?token=…%26app-token%3D%7BAPP_TOKEN%7D&launch=<handle>
302 …/realms/openmrs/login-actions/authenticate?session_code=…&app-token=<signed launch token>
200 http://localhost:3000/?state=…&code=<authorization code>
```

**Hop 1 — the module issues a handle.** The chart names an app and a patient; the module answers with
an opaque, single-use, 256-bit handle bound to this clinician. Deliberately not the patient's uuid:
that is guessable, discloses the context it exists to hide, and collides when two launches run for the
same patient.

**Hop 2 — the app learns almost nothing.** It receives `iss` and `launch`. Not the patient.

**Hop 3 — the app starts OAuth2.** `response_type=code` only, PKCE `S256`, and an `aud` naming the FHIR
server it intends to read. All three are SMART 2.x requirements.

**Hops 4 and 5 — the EHR vouches.** This is the part with no equivalent in a plain OIDC flow. Rather
than showing a login form, the authorization server redirects the browser *back into OpenMRS*, handing
it the URL to return to with an unfilled slot in it:

```java
// SmartLaunchAccessAuthenticator, in the Keycloak plugin
final String accessCode = context.generateAccessCode();
final String actionUrl  = context.getActionUrl(accessCode).toString();
final String submitUrl  = actionUrl + "&app-token={APP_TOKEN}";
```

Because that redirect goes to an OpenMRS URL, the browser attaches the **OpenMRS session cookie**, so
the module knows who is signed in without asking anyone. It redeems the handle and signs a token:

```java
// SmartAccessConfirmation
SmartSession smartSession = new SmartLaunchContextService().redeem(launchId,
    SmartLaunchContextService.identify(user));          // single use, and only by its owner

JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
    .subject(SmartLaunchContextService.identify(user)); // falls back to system_id: admin has no username
claims.claim(PATIENT_NAME, smartSession.getPatientUuid());
```

which decodes to exactly this:

```json
{ "alg": "HS256" }
{ "sub": "doctor", "patient": "c3ab5d9b-…", "iat": 1786648219, "exp": 1786648519 }
```

Five minutes, HMAC-SHA256, signed with the shared secret. The browser carries it back into the slot,
and the authorization server checks **the signature, not the session** — it never sees the OpenMRS
cookie. Cookie domains and cross-site restrictions between the two are irrelevant here, which is worth
knowing before troubleshooting in that direction.

**Why no login form appears.** The realm's browser flow decides it, by ordering:

```
SMART browser flow
  REQUIRED     smart-audience-validator          ← aud is checked before anything else
  REQUIRED     SMART authentication
                 ALTERNATIVE  auth-cookie                   ← an existing Keycloak SSO session
                 ALTERNATIVE  smart-access-authenticator    ← the vouching above
                 ALTERNATIVE  SMART forms  →  smart-username-password-form
```

Keycloak stops at the first alternative that succeeds. An EHR launch carries a `launch` handle, the
vouching alternative handles it, and the form — which lives in the third branch — is never reached. A
standalone launch has no handle, that alternative declines with `context.attempted()`, and the form
runs. Same realm, both behaviours.

**Hop 6 — the token response.** Real, from the exchange above:

```json
{
  "access_token": "eyJhbGciOiJSUzI1Ni…",
  "expires_in": 300,
  "token_type": "Bearer",
  "scope": "openid launch profile fhirUser patient/Patient.rs",
  "patient": "c3ab5d9b-ba13-44f4-9f7f-85db029283ff"
}
```

`patient` sits beside the token, **not inside it** — see [Where launch context actually
lives](#where-launch-context-actually-lives).

## The standalone launch, on the wire

Same destination, opened cold: no EHR session, no handle, so the clinician authenticates and then says
which patient. Two differences matter.

**The authorization server asks for credentials**, checked against the OpenMRS user table through the
federation provider — one set of credentials, one place to disable an account.

**Patient selection lands on a servlet, never on a frontend route.** The picker is an O3 route, and the
O3 app shell renders *no* route without a session: it redirects to the login page, discarding the
launch token in the URL. So the authorization server is pointed at `/ms/smartPatientSelection`, which
sits behind the bypass filter, becomes a session, and only then redirects to the picker:

```java
// SmartPatientSelectionServlet — after the filter has authenticated from the token
StringBuilder target = new StringBuilder(request.getContextPath())
    .append("/spa/smart/select-patient")
    .append("?token=").append(URLEncoder.encode(token, "UTF-8"));
response.sendRedirect(target.toString());   // deliberately not encodeRedirectURL: no jsessionid in the URL
```

This is the failure that passed a full `curl` walk while being broken in every browser — `curl` never
runs the app shell.

When the clinician chooses, `/ms/smartLaunchOptionSelected` signs the choice and hands back. It refuses
an unauthenticated caller with `401`, a target that is not the authorization server with `400`, and
`launch/encounter` with `501` — there is no visit-selection screen yet.

## Reading FHIR with the token

The access token is a normal RS256 JWT signed by the authorization server. Its real claims:

```
alg=RS256, kid=tURRzsLE7oUF…
claims: aud, azp, exp, iat, iss, jti, preferred_username, scope, sid, typ
aud    : http://localhost/openmrs/ws/fhir2/R4
scope  : openid launch profile fhirUser patient/Patient.rs
preferred_username: doctor
```

`preferred_username` is what names the OpenMRS user; without it the token verifies and still names
nobody, which is why the module logs that case specifically. Verification is deliberately narrow:

```java
processor.setJWSKeySelector(new JWSVerificationKeySelector<>(PERMITTED_ALGORITHMS, keySource));
List<String> required = Arrays.asList("exp", "iss", "aud");
JWTClaimsSet expected = new JWTClaimsSet.Builder().issuer(config.getIssuer()).build();
DefaultJWTClaimsVerifier<SecurityContext> claimsVerifier =
        new DefaultJWTClaimsVerifier<>(config.getAudience(), expected, new HashSet<>(required));
claimsVerifier.setMaxClockSkew(config.getAllowedClockSkewSeconds());
```

Asymmetric algorithms only — accepting an HMAC here would mean accepting a token signed with a secret
this module also holds. `exp` is *required*, not merely honoured when present. `aud` is checked as
membership, per RFC 7519 §4.1.3, because Keycloak issues multi-audience tokens and exact equality
rejected them.

A call, and the two ways it can be refused:

```console
$ curl -H "Authorization: Bearer $ACCESS_TOKEN" $FHIR/Patient/c3ab5d9b-…
200 OK

$ curl -i $FHIR/Patient                       # no Authorization header at all
HTTP/1.1 401
Content-Type: text/html;charset=UTF-8          ← fhir2's own refusal; this module never sees it

$ curl -i -H "Authorization: Bearer nonsense" $FHIR/Patient
HTTP/1.1 401
WWW-Authenticate: Bearer error="invalid_token" ← this module's filter
```

The token is proof for one request, not a login: the filter is mapped to the FHIR API alone and logs
out in a `finally`. The filter it replaced was mapped to every request in the webapp.

## Where launch context actually lives

Worth stating plainly, because the module currently gets this wrong in a way that is invisible.

SMART 2.x puts launch context in the **token response**, which only the app sees. The resource server
receives the access token, and the access token has no `patient` claim — verified above by decoding a
real one. The realm's mapper is configured as though it did:

```
access.token.claim         = true
access.tokenResponse.claim = true
```

but the mapper implements only the response half:

```java
public class SmartContextClaimMapper extends AbstractOIDCProtocolMapper
        implements OIDCAccessTokenResponseMapper {
```

so `access.token.claim` can never take effect. Consequently `SmartAccessToken.getPatient()`, the
`patient` on `SmartBearerCredentials`, and the request attributes the bearer filter sets are **always
null**. Nothing depends on them today, which is why nothing has failed — but granular scope
enforcement would, since it needs to know *which* patient a `patient/Patient.rs` token is scoped to,
and that fact is not in the token.

## Other decisions worth knowing

The launch and token mechanics are above; these are the rest.
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
- **Launch context never reaches the resource server.** The realm's context mapper sets
  `access.token.claim = true`, but implements only `OIDCAccessTokenResponseMapper`, so the access
  token carries no `patient`. Every read of it in this module is therefore null. Granular scope
  enforcement needs that fact and will have to get it another way.
- **`introspection_endpoint` is advertised without a client that can use it.** Introspection requires
  client authentication, and the token endpoint here offers only `client_secret_basic` and
  `private_key_jwt` — so a public app reading the discovery document finds an endpoint it cannot
  authenticate to. Either register a confidential client for it or stop advertising it.
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
