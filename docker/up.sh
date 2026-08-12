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
else
  note "provider jar present; pass --rebuild to rebuild it"
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
    "jwks-uri": internal_issuer + "/protocol/openid-connect/certs",
    "audience": audience,
    "username-claim": "preferred_username",
}, open(target, "w"), indent=2)
PYCFG
note "wrote target/openmrs-config/smart-oauth2.json (issuer/audience matched to the realm)"

# ------------------------------------------------------------------------ compose

log "Starting the stack"
export REFAPP_TAG="${REFAPP_TAG:-3.7.1}"
export KEYCLOAK_TAG="${KEYCLOAK_TAG:-26.7.1}"
export SMART_AUTH_JAR="$JAR_PATH"
export OPENMRS_PORT KEYCLOAK_PORT
docker compose up -d

log "Waiting for Keycloak"
for i in $(seq 1 60); do
  state="$(docker compose ps keycloak --format '{{.Health}}' 2>/dev/null || true)"
  [ "$state" = "healthy" ] && { note "keycloak healthy after ${i}s"; break; }
  docker compose logs keycloak 2>&1 | grep -qiE "FATAL|failed to start" \
    && { docker compose logs keycloak | tail -25; die "keycloak failed to start"; }
  [ "$i" = 60 ] && { docker compose logs keycloak | tail -25; die "keycloak did not become healthy"; }
  sleep 1
done

if docker compose logs keycloak 2>&1 | grep -q "Realm 'openmrs' imported"; then
  note "realm 'openmrs' imported"
else
  die "keycloak started but did not import the realm; check: docker compose logs keycloak"
fi

log "Waiting for OpenMRS (first start builds the database and can take minutes)"
for i in $(seq 1 120); do
  state="$(docker compose ps backend --format '{{.Health}}' 2>/dev/null || true)"
  [ "$state" = "healthy" ] && { note "openmrs healthy after $((i*5))s"; break; }
  [ "$i" = 120 ] && { docker compose logs backend | tail -25; die "openmrs did not become healthy"; }
  sleep 5
done

log "Ready"
cat <<EOF
    OpenMRS          $OPENMRS_BASE_URL
    O3 frontend      ${OPENMRS_BASE_URL}/spa
    FHIR base        ${OPENMRS_BASE_URL}/ws/fhir2/R4
    SMART discovery  ${OPENMRS_BASE_URL}/ws/fhir2/R4/.well-known/smart-configuration
    Keycloak admin   http://localhost:${KEYCLOAK_PORT}  (${KEYCLOAK_ADMIN:-admin}/${KEYCLOAK_ADMIN_PASSWORD:-admin})
    Realm            http://localhost:${KEYCLOAK_PORT}/realms/openmrs/.well-known/openid-configuration

    Verify the environment:  ./verify-env.sh
    Stop:                    ./up.sh --down
    Stop and wipe data:      ./up.sh --clean
EOF
