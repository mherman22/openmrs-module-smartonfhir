#!/usr/bin/env bash
#
# Brings up the SMART on FHIR development environment: RefApp 3.7.1 with Keycloak
# 26, the SMART provider installed, and the realm imported.
#
# Three things have to happen before the containers start, which is why this exists
# rather than a bare `docker compose up`:
#
#   1. The Keycloak provider JAR is built from the sibling
#      openmrs-contrib-keycloak-smart-auth checkout. It needs JDK 17+.
#   2. The realm is rendered from its template with a generated HS256 key and this
#      environment's URLs. Neither the key nor the URLs are committed.
#   3. The same secret is written where the OpenMRS module will read it, so both
#      ends of the app-token handshake agree.
#
# Usage:
#   ./up.sh                 bring the stack up and wait for it to be ready
#   ./up.sh --rebuild       force a rebuild of the provider JAR
#   ./up.sh --down          stop and remove containers, keeping volumes
#   ./up.sh --clean         stop and remove containers and volumes
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

SMART_AUTH_REPO="${SMART_AUTH_REPO:-$HERE/../../openmrs-contrib-keycloak-smart-auth}"
USERSTORE_REPO="${USERSTORE_REPO:-$HERE/../../openmrs-contrib-keycloak-auth}"
USERSTORE_JAR_PATH="$USERSTORE_REPO/openmrs-keycloak-userstore/target/openmrs-keycloak-userstore-1.0.0-SNAPSHOT.jar"
JAR_PATH="$SMART_AUTH_REPO/openmrs-keycloak-smart-auth/target/openmrs-keycloak-smart-auth-1.0.0-SNAPSHOT.jar"
OPENMRS_PORT="${OPENMRS_PORT:-80}"
KEYCLOAK_PORT="${KEYCLOAK_PORT:-8180}"
OPENMRS_BASE_URL="${OPENMRS_BASE_URL:-http://localhost:${OPENMRS_PORT}/openmrs}"
[ "$OPENMRS_PORT" = "80" ] && OPENMRS_BASE_URL="${OPENMRS_BASE_URL_OVERRIDE:-http://localhost/openmrs}"
SECRET_FILE="$HERE/target/smart-launch-secret"
REBUILD=0

log()  { printf '\n\033[1m==> %s\033[0m\n' "$1"; }
note() { printf '    %s\n' "$1"; }
die()  { printf '\n\033[31mERROR: %s\033[0m\n' "$1" >&2; exit 1; }

case "${1:-}" in
  --down)  docker compose down; exit 0 ;;
  --clean) docker compose down -v; rm -rf "$HERE/target"; exit 0 ;;
  --rebuild) REBUILD=1 ;;
  "") ;;
  *) die "unknown option: $1" ;;
esac

# ---------------------------------------------------------------- prerequisites

log "Checking prerequisites"
command -v docker >/dev/null || die "docker is not installed"
docker compose version >/dev/null 2>&1 || die "docker compose v2 is required"
docker version --format '{{.Server.Version}}' >/dev/null 2>&1 || die "the docker daemon is not running"
[ -d "$SMART_AUTH_REPO" ] || die "expected the Keycloak SPI checkout at $SMART_AUTH_REPO
Set SMART_AUTH_REPO to point at your clone of openmrs-contrib-keycloak-smart-auth."
note "keycloak SPI repo: $SMART_AUTH_REPO"

# ------------------------------------------------------------------ provider jar

if [ "$REBUILD" = 1 ] || [ ! -f "$JAR_PATH" ]; then
  log "Building the Keycloak provider"
  # Keycloak 26 requires Java 17+. Prefer an explicitly configured JDK, then fall
  # back to whatever java is on PATH, failing with a clear message if too old.
  if [ -n "${SMART_JAVA_HOME:-}" ]; then
    export JAVA_HOME="$SMART_JAVA_HOME"
  fi
  JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
  JAVA_MAJOR="$("${JAVA_BIN}" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)"
  [ "${JAVA_MAJOR:-0}" -ge 17 ] 2>/dev/null \
    || die "the provider needs JDK 17+, found ${JAVA_MAJOR:-unknown}.
Set SMART_JAVA_HOME to a suitable JDK, for example:
  SMART_JAVA_HOME=\$(/usr/libexec/java_home -v 21) ./up.sh --rebuild"
  note "building with JDK $JAVA_MAJOR"
  (cd "$SMART_AUTH_REPO" && mvn -q -B -ntp clean install)
  [ -f "$JAR_PATH" ] || die "the build did not produce $JAR_PATH"

  if [ -d "$USERSTORE_REPO" ]; then
    (cd "$USERSTORE_REPO" && mvn -q -B -ntp clean install) && note "built the user federation provider"
  fi
else
  note "provider jar present; pass --rebuild to rebuild it"
fi

# ------------------------------------------------------- federation provider deps

if [ -f "$USERSTORE_JAR_PATH" ]; then
  log "Staging the federation provider's JDBC driver"
  mkdir -p "$HERE/target/providers"
  # Keycloak ships a MySQL driver for its own datasource but does not expose it to provider
  # classloaders, and with an embedded database it is absent from the provider classpath
  # entirely. A provider's dependencies belong in providers/ beside it.
  CONNECTOR="$(find "$HOME/.m2/repository/com/mysql/mysql-connector-j" -name 'mysql-connector-j-*.jar' \
    ! -name '*sources*' 2>/dev/null | sort | tail -1)"
  if [ -n "$CONNECTOR" ]; then
    cp "$CONNECTOR" "$HERE/target/providers/mysql-connector-j.jar"
    note "staged $(basename "$CONNECTOR")"
  else
    die "could not find mysql-connector-j in the local Maven repository.
Build the federation provider first so the driver is downloaded:
  (cd $USERSTORE_REPO && mvn -q install)"
  fi
fi

# --------------------------------------------------------------- realm rendering

log "Rendering the realm"
mkdir -p "$HERE/target/import"

# The secret is generated once and reused, so restarting does not invalidate
# tokens the OpenMRS side is still configured for.
if [ ! -f "$SECRET_FILE" ]; then
  mkdir -p "$(dirname "$SECRET_FILE")"
  openssl rand -base64 32 > "$SECRET_FILE"
  chmod 600 "$SECRET_FILE"
  note "generated a new HS256 launch secret"
else
  note "reusing the existing launch secret"
fi

# SSL_REQUIRED=none because this stack is plain HTTP: Keycloak answers 403 on realm
# endpoints when a realm requires SSL and the request is not HTTPS.
SMART_LAUNCH_SECRET="$(cat "$SECRET_FILE")" \
  OPENMRS_BASE_URL="$OPENMRS_BASE_URL" \
  SSL_REQUIRED="${SSL_REQUIRED:-none}" \
  OPENMRS_JDBC_URL="${OPENMRS_JDBC_URL:-jdbc:mysql://db:3306/openmrs}" \
  OPENMRS_DB_USER="${OMRS_DB_USER:-openmrs}" \
  OPENMRS_DB_PASSWORD="${OMRS_DB_PASSWORD:-openmrs}" \
  python3 "$SMART_AUTH_REPO/realm/render-realm.py" "$HERE/target/import/openmrs-realm.json" \
  | sed 's/^/    /'

# The OpenMRS module reads the same key from its own config file. Written here so
# the two ends cannot drift apart.
mkdir -p "$HERE/target/openmrs-config"
python3 - "$SECRET_FILE" "$HERE/target/openmrs-config/smart-secret-key.json" <<'PY'
import json, sys
secret = open(sys.argv[1]).read().strip()
json.dump({"smart-shared-secret-key": secret}, open(sys.argv[2], "w"), indent=2)
PY
note "wrote target/openmrs-config/smart-secret-key.json for the OpenMRS module"

# The module needs to know which authorization server to trust, and which audience
# to insist on. Written here so it agrees with the realm rendered above: the audience
# must match what the app sends as aud, and what the Keycloak-side validator allows.
python3 - "$HERE/target/openmrs-config/smart-oauth2.json" \
  "http://keycloak:8080/realms/openmrs" \
  "http://localhost:${KEYCLOAK_PORT}/realms/openmrs" \
  "${OPENMRS_BASE_URL}/ws/fhir2/R4" <<'PYCFG'
import json, sys
target, internal_issuer, browser_issuer, audience = sys.argv[1:5]
json.dump({
    # Tokens are minted by Keycloak reached through the published port, so iss carries
    # that hostname; but this module fetches JWKS server-to-server over the compose
    # network, where the published port does not exist.
    "issuer": browser_issuer,
    # Keys are fetched over the compose network, where the published port does not exist.
    "jwks-uri": internal_issuer + "/protocol/openid-connect/certs",
    # An app reads the discovery document from outside and must be given a URL it can
    # resolve, so what is advertised is the browser-visible one.
    "advertised-jwks-uri": browser_issuer + "/protocol/openid-connect/certs",
    "audience": audience,
    "username-claim": "preferred_username",
}, open(target, "w"), indent=2)
PYCFG
note "wrote target/openmrs-config/smart-oauth2.json (issuer/audience matched to the realm)"



# ----------------------------------------------------------------- module staging

log "Staging the module"
mkdir -p "$HERE/target/modules"
STAGED_OMOD="$HERE/target/modules/smartonfhir.omod"
BUILT_OMOD="${SMARTONFHIR_OMOD_SOURCE:-$HERE/../omod/target/smartonfhir-2.0.0-SNAPSHOT.omod}"

if [ -f "$BUILT_OMOD" ]; then
  # cp, never mv: a bind mount of a single file follows the inode, and replacing the file
  # would leave the container reading the old one. Copying onto the existing path keeps it.
  cp "$BUILT_OMOD" "$STAGED_OMOD"
  note "staged $(basename "$BUILT_OMOD")"
  OMOD_CHANGED=1
elif [ -f "$STAGED_OMOD" ]; then
  note "no freshly built omod; keeping the staged one"
  OMOD_CHANGED=0
else
  die "no module to stage. Build it first:
  (cd .. && mvn clean install)"
fi

# ------------------------------------------------------------------------ compose

log "Starting the stack"
# Noted before starting: a container that compose creates fresh already reads the current
# mounts, and restarting one mid-first-start interrupts OpenMRS's database initialisation.
BACKEND_ID_BEFORE="$(docker compose ps -q backend 2>/dev/null || true)"
export REFAPP_TAG="${REFAPP_TAG:-3.7.1}"
export KEYCLOAK_TAG="${KEYCLOAK_TAG:-26.7.1}"
export SMART_AUTH_JAR="$JAR_PATH"
# Optional: the stack works without it, with Keycloak users maintained by hand instead.
if [ -f "$USERSTORE_JAR_PATH" ]; then export USERSTORE_JAR="$USERSTORE_JAR_PATH"; else
  export USERSTORE_JAR="$JAR_PATH"; note "no user federation provider built; Keycloak will not read OpenMRS users"
fi
export SMARTONFHIR_OMOD="$STAGED_OMOD"
export OPENMRS_PORT KEYCLOAK_PORT
docker compose up -d

# Keycloak is recreated every run. It holds no durable state by design, and realm import
# uses IGNORE_EXISTING: against a container whose realm already exists, an edited realm is
# silently ignored. Recreating it is what makes a realm change take effect at all. The
# database and OpenMRS data are untouched.
note "recreating Keycloak so the realm is imported fresh"
docker compose up -d --force-recreate keycloak >/dev/null

# OpenMRS loads modules at startup, so a newly staged omod needs a restart -- but only if
# the container was already running. compose will not recreate it for a changed mount
# target, yet a container it did just create has the new file already. Restarting a
# freshly started OpenMRS interrupts its first-run database initialisation and leaves the
# webapp undeployable.
BACKEND_ID_AFTER="$(docker compose ps -q backend 2>/dev/null || true)"
if [ "$OMOD_CHANGED" = 1 ] && [ -n "$BACKEND_ID_BEFORE" ] && [ "$BACKEND_ID_BEFORE" = "$BACKEND_ID_AFTER" ]; then
  note "restarting OpenMRS to load the staged module"
  docker compose restart backend >/dev/null
else
  note "OpenMRS started fresh; it already has the staged module"
fi

log "Waiting for Keycloak"
for i in $(seq 1 60); do
  state="$(docker compose ps keycloak --format '{{.Health}}' 2>/dev/null || true)"
  [ "$state" = "healthy" ] && { note "keycloak healthy after ${i}s"; break; }
  docker compose logs keycloak 2>&1 | grep -qiE "FATAL|failed to start" \
    && { docker compose logs keycloak | tail -25; die "keycloak failed to start"; }
  [ "$i" = 60 ] && { docker compose logs keycloak | tail -25; die "keycloak did not become healthy"; }
  sleep 1
done

imported=0
for _ in $(seq 1 15); do
  if docker compose logs keycloak 2>&1 | grep -q "Realm 'openmrs' imported"; then imported=1; break; fi
  sleep 2
done
if [ "$imported" = 1 ]; then
  note "realm 'openmrs' imported"
else
  die "keycloak started but did not import the realm; check: docker compose logs keycloak"
fi

# --------------------------------------------------------- development affordances
#
# Applied at runtime, never committed to the realm, so the realm template stays
# production-shaped: no direct-grant client and no user credentials in version control.

log "Adding development-only access to Keycloak"
SMART_DEV_USER="${SMART_DEV_USER:-doctor}"
SMART_DEV_PASSWORD="${SMART_DEV_PASSWORD:-Smart123}"

kcadm() { docker compose exec -T keycloak /opt/keycloak/bin/kcadm.sh "$@" 2>/dev/null; }
kcadm config credentials --server http://localhost:8080 --realm master --user "${KEYCLOAK_ADMIN:-admin}" \
  --password "${KEYCLOAK_ADMIN_PASSWORD:-admin}" >/dev/null \
  || die "could not authenticate kcadm inside the Keycloak container"

# Direct access grants let a test obtain a token without driving a browser. Enabled on
# smartClient itself rather than on a separate client, so what is exercised is the same
# client configuration a deployment uses, minus this one flag.
CLIENT_UUID="$(kcadm get clients -r openmrs -q clientId=smartClient --fields id --format csv --noquotes | head -1 | tr -d '\r')"
if [ -n "$CLIENT_UUID" ]; then
  kcadm update "clients/$CLIENT_UUID" -r openmrs -s directAccessGrantsEnabled=true >/dev/null \
    && note "enabled direct access grants on smartClient (development only)"
else
  die "smartClient not found in the openmrs realm"
fi

# With federation configured, Keycloak reads users from the OpenMRS database and a local
# user would only shadow them. One is created only when the provider is absent.
if [ -f "$USERSTORE_JAR_PATH" ]; then
  note "users come from OpenMRS via federation; no Keycloak user is created"
else
  if kcadm get users -r openmrs -q "username=$SMART_DEV_USER" --fields username --format csv --noquotes | grep -q "$SMART_DEV_USER"; then
    note "keycloak user '$SMART_DEV_USER' already present"
  else
    kcadm create users -r openmrs -s "username=$SMART_DEV_USER" -s enabled=true \
      -s "firstName=Demo" -s "lastName=Clinician" \
      -s "email=${SMART_DEV_USER}@example.org" -s emailVerified=true >/dev/null \
      && note "created keycloak user '$SMART_DEV_USER'"
  fi
  kcadm set-password -r openmrs --username "$SMART_DEV_USER" --new-password "$SMART_DEV_PASSWORD" >/dev/null \
    && note "set its password (development only)"
fi

log "Waiting for OpenMRS (first start builds the database and can take minutes)"
for i in $(seq 1 120); do
  state="$(docker compose ps backend --format '{{.Health}}' 2>/dev/null || true)"
  [ "$state" = "healthy" ] && { note "openmrs healthy after $((i*5))s"; break; }
  [ "$i" = 120 ] && { docker compose logs backend | tail -25; die "openmrs did not become healthy"; }
  sleep 5
done

# ------------------------------------------------- authentication module settings
#
# The authentication module reads its settings from openmrs-runtime.properties, filtered
# by the "authentication." prefix -- not from a file of its own. reloadConfigFromRuntimeProperties
# takes the application name and uses the prefix as a key filter, so a separate
# authentication-runtime.properties is never consulted. This is therefore also the
# instruction for a real deployment: put these lines in openmrs-runtime.properties.
#
# The file is generated by the image on first initialisation, so it can only be amended
# once OpenMRS has started once. Appending is idempotent.

log "Registering the bearer scheme with the authentication module"
RUNTIME_PROPS=/openmrs/data/openmrs-runtime.properties
SCHEME_CLASS=org.openmrs.module.smartonfhir.web.smart.SmartBearerTokenAuthenticationScheme

if docker compose exec -T --user root backend sh -c "grep -q '^authentication.scheme=' $RUNTIME_PROPS" 2>/dev/null; then
  note "already registered"
else
  docker compose exec -T --user root backend sh -c "cat >> $RUNTIME_PROPS <<'PROPS'

# SMART on FHIR: authenticate FHIR requests carrying a SMART access token. Non-bearer
# credentials fall through to the platform's username/password scheme, so ordinary
# logins are unaffected.
authentication.scheme=smartBearer
authentication.scheme.smartBearer.type=$SCHEME_CLASS
# Everything is whitelisted, deliberately. The scheme is registered so that
# Context.authenticate can route SMART credentials to it -- not to make the
# authentication module the gatekeeper for the webapp. Its filter is mapped to /* and,
# for any non-whitelisted request from an unauthenticated caller, calls
# handleAuthenticationFailure even when the scheme offers no challenge URL; on the
# OpenMRS root that produces a redirect loop. Whether to enforce authentication centrally
# is an implementer's decision, and turning it on is not this module's business.
authentication.whiteList=/*
PROPS" || die "could not amend $RUNTIME_PROPS"
  note "added authentication.scheme=smartBearer to openmrs-runtime.properties"

  note "restarting OpenMRS to pick it up"
  docker compose restart backend >/dev/null
  for i in $(seq 1 120); do
    [ "$(docker compose ps backend --format '{{.Health}}' 2>/dev/null)" = "healthy" ] && break
    [ "$i" = 120 ] && die "openmrs did not come back healthy"
    sleep 5
  done
  note "openmrs healthy again"
fi

log "Ready"
cat <<EOF
    OpenMRS          $OPENMRS_BASE_URL
    O3 frontend      ${OPENMRS_BASE_URL}/spa
    FHIR base        ${OPENMRS_BASE_URL}/ws/fhir2/R4
    SMART discovery  ${OPENMRS_BASE_URL}/ws/fhir2/R4/.well-known/smart-configuration
    Keycloak admin   http://localhost:${KEYCLOAK_PORT}  (${KEYCLOAK_ADMIN:-admin}/${KEYCLOAK_ADMIN_PASSWORD:-admin})
    Realm            http://localhost:${KEYCLOAK_PORT}/realms/openmrs/.well-known/openid-configuration

    Dev login        ${SMART_DEV_USER:-doctor} with that user's own OpenMRS password

    Verify the environment:  ./verify-env.sh
    Stop:                    ./up.sh --down
    Stop and wipe data:      ./up.sh --clean
EOF
