# SMART on FHIR development environment

Brings up **OpenMRS Reference Application 3.7.1** alongside **Keycloak 26**, with the
SMART provider installed and the SMART realm imported. This is the environment the
2.0 revamp is developed against.

```bash
cp .env.example .env       # optional; every value has a working default
./up.sh                    # build the provider, render the realm, start the stack
./verify-env.sh            # assert the environment is actually usable
```

First run pulls several GB and OpenMRS builds its database, so expect it to take a
while. Subsequent runs are fast.

| | |
|---|---|
| OpenMRS | <http://localhost/openmrs> |
| O3 frontend | <http://localhost/openmrs/spa> |
| FHIR base | <http://localhost/openmrs/ws/fhir2/R4> |
| SMART discovery | <http://localhost/openmrs/ws/fhir2/R4/.well-known/smart-configuration> |
| Keycloak | <http://localhost:8180> (`admin`/`admin`) |
| Dev clinician | `doctor` / `Smart123` — a Keycloak user matching an OpenMRS demo user |

## Requirements

- Docker with Compose v2, and a running daemon
- **JDK 17 or newer** to build the Keycloak provider. If your default `java` is
  older, point `SMART_JAVA_HOME` at a suitable JDK:
  ```bash
  SMART_JAVA_HOME=$(/usr/libexec/java_home -v 21) ./up.sh --rebuild
  ```
- A checkout of [`openmrs-contrib-keycloak-smart-auth`](https://github.com/mherman22/openmrs-contrib-keycloak-smart-auth)
  beside this repository, or `SMART_AUTH_REPO` pointing at it.

## Why `up.sh` rather than `docker compose up`

Three things must happen before the containers start, and Keycloak starts without
any of them if you skip the script:

1. **The provider JAR is built** from the sibling Keycloak SPI checkout.
2. **The realm is rendered** from its template. The committed realm holds
   placeholders, never a secret and never an environment's hostnames, so it has to
   be rendered with a generated HS256 key and this environment's URLs.
3. **The same secret is written where the OpenMRS module reads it**
   (`target/openmrs-config/smart-secret-key.json`), so both ends of the app-token
   handshake agree. They are useless if they drift.

Everything `up.sh` generates lands under `target/`, which is git-ignored.

## How the bearer scheme is registered

The authentication module reads its settings from **`openmrs-runtime.properties`**, filtered
by the `authentication.` prefix — not from a file of its own. `up.sh` therefore appends:

```properties
authentication.scheme=smartBearer
authentication.scheme.smartBearer.type=org.openmrs.module.smartonfhir.web.smart.SmartBearerTokenAuthenticationScheme
```

That is also the instruction for a real deployment. Two things about it are easy to get
wrong:

- A separate `authentication-runtime.properties` is **never read**.
  `reloadConfigFromRuntimeProperties` takes the *application* name and uses
  `authentication` as a key prefix.
- The scheme is registered so that `Context.authenticate` can route SMART credentials to
  it. It deliberately returns no credentials and no challenge URL of its own, because the
  authentication module's filter is mapped to `/*`: a scheme that returns credentials there
  makes that filter authenticate the request and then issue its interactive-login **success
  redirect**, which is a 302 where a FHIR client expects data. Reading the bearer header is
  `SmartBearerTokenFilter`'s job, scoped to the FHIR paths.

## Development-only Keycloak changes

`up.sh` applies two things at runtime that are **not** in the committed realm, so the realm
stays production-shaped and no credential is in version control:

- direct access grants on `smartClient`, so a test can obtain a token without a browser;
- a Keycloak user matching an OpenMRS demo user. Until the user-federation provider is
  ported, Keycloak has no knowledge of OpenMRS users, so the two are lined up by hand.

Keycloak is recreated on every run. It holds no durable state by design, and realm import
uses `IGNORE_EXISTING` — against a container whose realm already exists, an edited realm is
silently ignored, so recreating it is what makes a realm change take effect at all.

## URLs have to be browser-reachable

The SMART launch and patient-selection URLs in the realm are **browser redirects**.
A container-internal hostname such as `http://backend:8080` cannot work: the
browser has to resolve it. Likewise the `aud` value the audience validator accepts
must be the FHIR base *as the app names it*, not as another container sees it. This
is why the realm is rendered per-environment rather than committed with URLs in it.

If you change `OPENMRS_PORT`, re-run `./up.sh` so the realm is re-rendered.

## Commands

| | |
|---|---|
| `./up.sh` | build, render, start, wait for readiness |
| `./up.sh --rebuild` | force a rebuild of the provider JAR |
| `./up.sh --down` | stop and remove containers, keep data |
| `./up.sh --clean` | stop and remove containers **and volumes** |
| `./verify-env.sh` | assert the environment works |
| `docker compose logs -f keycloak` | follow Keycloak |

## What `verify-env.sh` checks

Beyond "the containers are up": that OpenMRS answers through the gateway, that the
O3 frontend is served, that `/ws/rest/v1/session` responds (the ESM app shell will
not boot without it), that the FHIR endpoint is mapped, that Keycloak advertises
PKCE `S256` and the SMART launch scopes, that all four SMART providers registered,
and that **a launch naming the wrong FHIR server is rejected before the login form**.

## Keycloak is pinned on purpose

Every SPI the provider implements is internal to Keycloak — the server logs
`KC-SERVICES0047` for each one — so a minor upgrade can break it with no
deprecation cycle. Before moving `KEYCLOAK_TAG`, run
`realm/verify-realm-import.sh <new-version>` in the SPI repository and confirm it
is green.

## This is a development configuration

Keycloak runs `start-dev` with an embedded database over plain HTTP, and the
credentials here are the defaults. Do not model a deployment on it.

## Status

RefApp 3.7.1 and Keycloak 26 run together with the `smartonfhir` omod installed and
configured. `verify-env.sh` covers the whole bearer path, including obtaining a real token
and reading FHIR data with it.

Not yet built: the patient-selection UI, so the interactive launch flows cannot be walked
end to end in a browser; the Keycloak user-federation provider, which is why the dev user
is lined up by hand; and granular scope enforcement, which belongs in fhir2's resource
providers rather than here.
