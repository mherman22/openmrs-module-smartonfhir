# Bringing your own SMART app

This module and the Keycloak provider exist so that **your** app can read OpenMRS clinical data
without either side knowing anything about the other. Nothing here is specific to the patient-chart
app that ships with it — a risk dashboard, a growth-chart viewer, a reporting tool or a decision
support widget all use the same three steps.

You write no OpenMRS code and deploy no OpenMRS module. You register a client, launch it, and call
FHIR.

## What you are building against

| | |
|---|---|
| **Authorization server** | Keycloak. Authenticates the clinician against their existing OpenMRS credentials, and issues your tokens. |
| **Resource server** | OpenMRS + the FHIR2 module. Verifies your token and answers FHIR requests. |
| **Discovery** | `GET {fhir-base}/.well-known/smart-configuration` — every endpoint you need, including where to log out. Read it rather than hardcoding. |

Everything below was verified against a running stack; the numbers in the worked example are real
responses.

## 1. Register your client

Once, by whoever administers the Keycloak realm:

```bash
kcadm.sh create clients -r openmrs \
  -s clientId=risk-dashboard \
  -s name="Patient Risk Dashboard" \
  -s enabled=true \
  -s publicClient=true \
  -s standardFlowEnabled=true \
  -s 'redirectUris=["https://risk.example.org/callback*"]' \
  -s 'attributes."pkce.code.challenge.method"=S256'
```

| Field | Why |
|---|---|
| `publicClient=true` | Use for browser and mobile apps, which cannot keep a secret. A server-side app should be confidential instead, with `publicClient=false` and a generated secret. |
| `pkce.code.challenge.method=S256` | SMART App Launch 2.x **requires** S256 and forbids `plain`. The discovery document advertises `S256` only. |
| `redirectUris` | Exact, and as narrow as you can make it. This is what stops an authorization code being delivered somewhere else. |
| `standardFlowEnabled` | Authorization code flow. Leave `directAccessGrantsEnabled` off; password grant is not a SMART launch. |

**You do not need to attach scopes.** A newly registered client inherits what the resource server
requires and can request any SMART scope the realm defines:

```
default scopes  : profile fhir-audience
optional scopes : launch launch/patient launch/encounter fhirUser
                  patient/Patient.rs patient/Observation.rs patient/Condition.rs patient/Encounter.rs
```

Those two defaults are not cosmetic, and removing them produces a launch that appears to succeed and
then fails on every FHIR call:

- **`fhir-audience`** stamps the OpenMRS FHIR server into your token's `aud`. Without it the resource
  server refuses every token with `401` and logs `JWT missing required audience`.
- **`profile`** carries the `preferred_username` claim, which is how the resource server works out
  which OpenMRS user you are acting as. Without it, `401` and `carries no 'preferred_username' claim`.

Both were once attached to a single client, and the second app registered against the realm hit
exactly this. A realm contract test now asserts they stay realm defaults.

## 2. Start a launch

Send the clinician's browser to the `authorization_endpoint` from the discovery document:

```
GET {authorization_endpoint}
  ?response_type=code
  &client_id=risk-dashboard
  &redirect_uri=https%3A%2F%2Frisk.example.org%2Fcallback
  &scope=openid+fhirUser+launch%2Fpatient+patient%2FObservation.rs+patient%2FCondition.rs
  &aud={fhir-base}
  &state={random}
  &code_challenge={S256 of your verifier}
  &code_challenge_method=S256
```

**`aud` is mandatory.** It must name the FHIR server you intend to call. A launch that omits it, or
names another server, is refused *before* the clinician sees a login form — deliberately, so a token
minted for one FHIR server can never be presented to another. Trailing-slash differences are
tolerated; nothing else is.

**Ask for `launch/patient` if your app is about one patient.** That is what causes the clinician to be
shown a patient-selection screen; the patient they choose is what your app is granted. If you do not
ask for it, no patient is selected and no patient context is returned.

What happens next is none of your concern, but for orientation: the clinician signs in with their
ordinary OpenMRS username and password (Keycloak reads users from the OpenMRS database — there is no
second user directory), chooses a patient, and the browser comes back to your `redirect_uri` with a
`code`.

## 3. Exchange the code

```bash
curl -X POST {token_endpoint} \
  -d grant_type=authorization_code \
  -d client_id=risk-dashboard \
  -d code={code} \
  -d redirect_uri=https://risk.example.org/callback \
  -d code_verifier={your verifier}
```

```json
{
  "access_token": "eyJ…",
  "token_type": "Bearer",
  "expires_in": 300,
  "scope": "openid fhirUser launch/patient patient/Observation.rs patient/Condition.rs profile",
  "patient": "b925b60f-8751-4b2f-b38a-a35002961be4",
  "id_token": "eyJ…"
}
```

**Launch context arrives in the token response, not inside the JWT.** `patient` is a sibling of
`access_token`, exactly as SMART specifies. Decoding the access token and looking for a `patient`
claim will find nothing — that is correct behaviour, not a bug.

Keep the `id_token`. You need it to log out cleanly (step 5).

## 4. Call FHIR

```bash
curl -H "Authorization: Bearer {access_token}" \
  "{fhir-base}/Observation?patient={patient}&_count=50"
```

Every FHIR resource the FHIR2 module supports is available. The token is verified per request against
the authorization server's published signing keys; it is not a login and establishes no session.

**Be aware of what is not yet enforced.** Scopes are parsed, advertised and granted, but the resource
server does not currently restrict requests by them: a token granted only `patient/Observation.rs` can
in practice read other resource types, and the *user's* OpenMRS privileges are what actually apply.
`permission-v2` is deliberately absent from the advertised capabilities for this reason. Treat scopes
as your declaration of intent, and do not rely on them as a boundary yet.

A refused token answers `401` with `WWW-Authenticate: Bearer error="invalid_token"`. Refresh and retry;
if it still fails, check the server log, which names the reason.

## 5. Log out

Ending your own session is not enough. The authorization server keeps its own session, and the next
launch in that browser is granted **silently, as the previous clinician** — which on a shared clinical
workstation is somebody else's identity. Send the browser to the `end_session_endpoint` from the
discovery document:

```
GET {end_session_endpoint}
  ?id_token_hint={the id_token you kept}
  &post_logout_redirect_uri=https%3A%2F%2Frisk.example.org%2F
```

`id_token_hint` is what lets Keycloak end the session without stopping to ask the clinician "do you
want to log out?". Register your `post_logout_redirect_uri` on the client.

## Supporting a resource type the realm does not define yet

Scopes are Keycloak client scopes. Asking for one that does not exist is rejected outright — your app
is redirected back with:

```
?error=invalid_scope&error_description=Invalid+scopes:+openid+patient/MedicationRequest.rs
```

The realm ships `Patient`, `Observation`, `Condition` and `Encounter`. To add another:

```bash
kcadm.sh create client-scopes -r openmrs \
  -s name="patient/MedicationRequest.rs" -s protocol=openid-connect \
  -s 'attributes."include.in.token.scope"=true'
# then add it to the realm's default optional scopes so every client may request it
```

Prefer adding it to the realm's `defaultOptionalClientScopes` over attaching it to one client, for the
same reason as above: one client working is not the same as the realm working.

## Worked example: an app that is not a chart viewer

A risk dashboard wants observations and conditions for one patient. It never opens a chart.

1. Registered exactly as in step 1 — `risk-dashboard`, no scope wiring.
2. Launched with `scope=openid fhirUser launch/patient patient/Observation.rs patient/Condition.rs`.
3. The clinician signed in with their OpenMRS password and chose a patient.
4. The token response carried `patient=b925b60f-…` and the granted scopes.
5. The app read **22 conditions** and **61 observations** for that patient.

Nothing in the module, the Keycloak provider or the patient-selection screen was changed or configured
for it. The patient-selection screen is shared: any app asking for `launch/patient` gets it, and it is
the same screen for all of them.

## What you do not get yet

| | |
|---|---|
| **EHR launch** | Launching your app *from within* the OpenMRS UI is not usable: there is no app registry and no affordance in the patient chart. Standalone launch is the supported path. |
| **`launch/encounter`** | The visit-selection screen was removed with the RefApp 2.x UI and has no replacement. Requesting it will not complete. |
| **Scope enforcement** | See step 4. |
| **Dynamic client registration** | Not enabled. Registration is an administrative step. |
| **Backend Services** | The `client_credentials` flow for app-to-app access is not part of this work. |

## Testing your integration

`smart-test-app.py` in the [distribution repository](https://github.com/mherman22/openmrs-distro-smartonfhir)
is a complete, ~250-line SMART client: it starts a launch, receives the redirect, exchanges the code
with PKCE, reads what it was granted, and logs out. It is the shortest correct reference for all five
steps above, and it is configurable, so you can point it at your own client before writing any code:

```bash
CLIENT_ID=risk-dashboard PORT=3100 \
  SCOPE="openid fhirUser launch/patient patient/Observation.rs" \
  ./smart-test-app.py
```

Run `./verify-env.sh` in the same repository to confirm the server side is sound before you debug your
app — it walks a whole launch and checks 58 things, including the audience validation and logout
endpoints your app depends on.

## Symptoms and causes

| Symptom | Cause |
|---|---|
| Refused before the login form appears | `aud` missing, or not naming this FHIR server |
| `error=invalid_scope` at your redirect URI | A requested scope is not defined in the realm |
| Launch succeeds, every FHIR call `401` | Your client lost the `fhir-audience` or `profile` default scope |
| No `patient` in the token response | You did not request `launch/patient` |
| No `patient` claim inside the access token | Correct — launch context is in the token response |
| The next launch never asks for a password | You ended your session but not the authorization server's; use `end_session_endpoint` |
| Redirected to a location-picker screen mid-launch | The OpenMRS session has no login location |
| `401` with an HTML body from a REST endpoint | A stale session cookie for a session that no longer exists; clear cookies |
