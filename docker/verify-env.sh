#!/usr/bin/env bash
#
# Asserts that the development environment is actually usable, rather than merely
# running. Every check corresponds to something a SMART launch depends on.
#
# Usage: ./verify-env.sh
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

OPENMRS_PORT="${OPENMRS_PORT:-80}"
KEYCLOAK_PORT="${KEYCLOAK_PORT:-8180}"
if [ "$OPENMRS_PORT" = "80" ]; then OPENMRS="http://localhost/openmrs"; else OPENMRS="http://localhost:${OPENMRS_PORT}/openmrs"; fi
KC="http://localhost:${KEYCLOAK_PORT}"
FAILURES=0

step()  { printf '\n=== %s ===\n' "$1"; }
pass()  { printf '  PASS  %s\n' "$1"; }
fail()  { printf '  FAIL  %s\n        %s\n' "$1" "${2:-}"; FAILURES=$((FAILURES+1)); }
check() { if [ "$2" = "$3" ]; then pass "$1"; else fail "$1" "expected '$3', got '$2'"; fi; }

# -L throughout: OpenMRS answers several of these through a redirect, and a 301 is
# not a failure.
status() { curl -sL -o /dev/null -w '%{http_code}' --max-time 20 "$1" 2>/dev/null || echo 000; }

step "Containers"
for svc in db backend frontend gateway keycloak; do
  state="$(docker compose ps "$svc" --format '{{.State}}' 2>/dev/null | head -1)"
  if [ "$state" = "running" ]; then pass "$svc is running"; else fail "$svc is running" "state='$state'"; fi
done

step "OpenMRS"
check "OpenMRS answers through the gateway" "$(status "$OPENMRS/")" "200"
check "the O3 frontend is served"           "$(status "$OPENMRS/spa/")" "200"
SESSION="$(curl -sL --max-time 20 "$OPENMRS/ws/rest/v1/session" 2>/dev/null)"
if printf '%s' "$SESSION" | grep -q '"authenticated"'; then
  pass "the session endpoint responds (the ESM app shell depends on it)"
else
  fail "the session endpoint responds" "got: $(printf '%s' "$SESSION" | head -c 120)"
fi

step "FHIR"
# fhir2 requires authentication, so 401 is the healthy answer for an anonymous call:
# it proves the endpoint is mapped rather than missing.
code="$(status "$OPENMRS/ws/fhir2/R4/metadata")"
if [ "$code" = "200" ] || [ "$code" = "401" ]; then
  pass "the FHIR R4 endpoint is mapped (HTTP $code)"
else
  fail "the FHIR R4 endpoint is mapped" "HTTP $code; the fhir2 module may not have started"
fi

step "Keycloak"
check "the openmrs realm is served" "$(status "$KC/realms/openmrs/.well-known/openid-configuration")" "200"
DISC="$(curl -sL --max-time 20 "$KC/realms/openmrs/.well-known/openid-configuration" 2>/dev/null)"

got="$(printf '%s' "$DISC" | python3 -c "
import sys,json
try:
    d=json.load(sys.stdin)
    print('S256' if 'S256' in d.get('code_challenge_methods_supported',[]) else 'missing')
except Exception: print('unparseable')")"
check "PKCE S256 is advertised" "$got" "S256"

got="$(printf '%s' "$DISC" | python3 -c "
import sys,json
try:
    d=json.load(sys.stdin)
    scopes=d.get('scopes_supported',[])
    want={'launch','launch/patient','launch/encounter','fhirUser'}
    print('all' if want.issubset(set(scopes)) else 'missing: %s' % sorted(want-set(scopes)))
except Exception: print('unparseable')")"
check "the SMART launch scopes are advertised" "$got" "all"

step "SMART providers are loaded"
# Captured once to a file. Piping docker logs straight into `grep -q` breaks under
# `set -o pipefail`: grep exits on the first match, docker dies of SIGPIPE, and the
# pipeline reports failure despite the match.
KC_LOG="$(mktemp)"
docker compose logs keycloak > "$KC_LOG" 2>&1 || true
for provider in smart-audience-validator smart-access-authenticator smart-application-authenticator smart-username-password-form smart-context-claim-mapper; do
  if grep -q "$provider" "$KC_LOG"; then
    pass "$provider registered"
  else
    fail "$provider registered" "not mentioned in the Keycloak log"
  fi
done
if grep -q "Realm 'openmrs' imported" "$KC_LOG"; then
  pass "the realm was imported"
else
  fail "the realm was imported" "no import line in the Keycloak log"
fi

step "The SMART discovery document"
DISCO="$(curl -sL --max-time 20 "$OPENMRS/ws/fhir2/R4/.well-known/smart-configuration" 2>/dev/null)"
got="$(printf '%s' "$DISCO" | python3 -c "
import sys,json
try:
    d=json.load(sys.stdin)
except Exception:
    print('unparseable'); raise SystemExit
missing=[k for k in ('authorization_endpoint','token_endpoint','capabilities','scopes_supported') if not d.get(k)]
print('complete' if not missing else 'missing: %s' % missing)")"
check "the module serves a discovery document" "$got" "complete"

# The endpoints an app is told to use must be ones a browser can reach, not
# container-internal hostnames.
got="$(printf '%s' "$DISCO" | python3 -c "
import sys,json
try: d=json.load(sys.stdin)
except Exception: print('unparseable'); raise SystemExit
a=d.get('authorization_endpoint','')
print('reachable' if a.startswith('http://localhost:') else 'unreachable: %s' % a)")"
check "the advertised authorization endpoint is browser-reachable" "$got" "reachable"

got="$(printf '%s' "$DISCO" | python3 -c "
import sys,json
try: d=json.load(sys.stdin)
except Exception: print('unparseable'); raise SystemExit
want={'launch-ehr','launch-standalone','context-ehr-patient','context-standalone-patient'}
have=set(d.get('capabilities',[]))
print('all' if want.issubset(have) else 'missing: %s' % sorted(want-have))")"
check "the launch capabilities are advertised" "$got" "all"

step "The audience validator decides launches on the aud parameter"
PKCE="code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&code_challenge_method=S256"
REDIRECT="$(python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))" "$OPENMRS/")"
authorize() {
  # $1 is the aud value. Status code only: Keycloak renders its error page with the
  # login theme, so the page text cannot distinguish a rejection from a login form.
  curl -s -o "$2" -w '%{http_code}' --max-time 20 \
    "$KC/realms/openmrs/protocol/openid-connect/auth?client_id=smartClient&response_type=code&scope=openid&redirect_uri=$REDIRECT&aud=$(python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))" "$1")&$PKCE" 2>/dev/null || echo 000
}

WRONG_BODY="$(mktemp)"; RIGHT_BODY="$(mktemp)"
check "a launch naming another FHIR server is refused" "$(authorize "https://attacker.example.org/fhir" "$WRONG_BODY")" "400"
check "a launch naming this FHIR server is admitted"   "$(authorize "$OPENMRS/ws/fhir2/R4" "$RIGHT_BODY")" "200"

# Only the admitted launch should reach a form that asks for credentials.
if grep -qiE 'type="password"|name="password"' "$RIGHT_BODY"; then
  pass "the admitted launch reaches the login form"
else
  fail "the admitted launch reaches the login form" "no password field in the response"
fi
if grep -qiE 'type="password"|name="password"' "$WRONG_BODY"; then
  fail "the refused launch stops before the login form" "a password field was rendered"
else
  pass "the refused launch stops before the login form"
fi
rm -f "$WRONG_BODY" "$RIGHT_BODY" "$KC_LOG"

step "SMART access tokens are actually examined on FHIR requests"
# A 401 alone proves nothing: fhir2 answers 401 to any unauthenticated call. What
# distinguishes our path is the OAuth challenge header and our verifier's own log line.
HDRS="$(mktemp)"
code="$(curl -s -D "$HDRS" -o /dev/null -w '%{http_code}' --max-time 20 \
  -H "Authorization: Bearer not-a-real-token" "$OPENMRS/ws/fhir2/R4/Patient" 2>/dev/null || echo 000)"
check "a bad bearer token is refused" "$code" "401"
if grep -qi 'WWW-Authenticate: *Bearer error="invalid_token"' "$HDRS"; then
  pass "the refusal carries an OAuth bearer challenge"
else
  fail "the refusal carries an OAuth bearer challenge" "no WWW-Authenticate: Bearer header"
fi
rm -f "$HDRS"

OMRS_LOG="$(mktemp)"
docker compose exec -T backend sh -c 'cat /openmrs/data/openmrs.log' > "$OMRS_LOG" 2>/dev/null || true
if grep -q "Rejected a SMART access token" "$OMRS_LOG"; then
  pass "the module's own verifier examined the token"
else
  fail "the module's own verifier examined the token" \
       "no rejection logged, so the bearer filter may not be running at all"
fi
rm -f "$OMRS_LOG"

# A structurally valid JWT gets past parsing into key resolution, so the rejection reason
# shows whether the authorization server's JWKS was actually consulted. Asserting on the
# module's INFO line would not work: OpenMRS logs this package at WARN.
WELL_FORMED="$(python3 -c "
import base64, json, time
def seg(d): return base64.urlsafe_b64encode(json.dumps(d).encode()).decode().rstrip('=')
print(seg({'alg':'RS256','kid':'no-such-key'}) + '.' +
      seg({'iss':'$KC/realms/openmrs','aud':'$OPENMRS/ws/fhir2/R4','preferred_username':'admin','exp':int(time.time())+300}) +
      '.' + base64.urlsafe_b64encode(b'not-a-real-signature').decode().rstrip('='))")"
code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 20 \
  -H "Authorization: Bearer $WELL_FORMED" "$OPENMRS/ws/fhir2/R4/Patient" 2>/dev/null || echo 000)"
check "a token signed by an unknown key is refused" "$code" "401"

OMRS_LOG2="$(mktemp)"
docker compose exec -T backend sh -c 'cat /openmrs/data/openmrs.log' > "$OMRS_LOG2" 2>/dev/null || true
if grep -qE "no matching key|Signed JWT rejected|Another algorithm expected" "$OMRS_LOG2"; then
  pass "the authorization server's signing keys were consulted"
else
  fail "the authorization server's signing keys were consulted" \
       "the rejection did not reach key resolution, so JWKS may be unreachable"
fi
rm -f "$OMRS_LOG2"

step "A genuine Keycloak token reads FHIR data"
# The end-to-end case. Everything above proves bad tokens are refused, which is the easier
# half; this proves a real one is accepted and maps to an OpenMRS user.
DEV_USER="${SMART_DEV_USER:-doctor}"
DEV_PASSWORD="${SMART_DEV_PASSWORD:-Smart123}"
TOKEN_JSON="$(curl -s --max-time 30 -X POST "$KC/realms/openmrs/protocol/openid-connect/token" \
  -d client_id=smartClient -d grant_type=password -d "username=$DEV_USER" -d "password=$DEV_PASSWORD" \
  -d "scope=openid profile fhirUser launch" 2>/dev/null)"
ACCESS_TOKEN="$(printf '%s' "$TOKEN_JSON" | python3 -c "
import sys,json
try: print(json.load(sys.stdin).get('access_token',''))
except Exception: print('')")"

if [ -z "$ACCESS_TOKEN" ]; then
  fail "an access token can be obtained for $DEV_USER" \
       "$(printf '%s' "$TOKEN_JSON" | head -c 160)"
else
  pass "an access token was obtained for $DEV_USER"

  # aud must name this FHIR server or the module refuses the token, and the username claim
  # is what it maps onto an OpenMRS user.
  claims="$(printf '%s' "$ACCESS_TOKEN" | python3 -c "
import sys,json,base64
p=sys.stdin.read().split('.')[1]; p+='='*(-len(p)%4)
c=json.loads(base64.urlsafe_b64decode(p))
aud=c.get('aud'); aud=aud if isinstance(aud,list) else [aud]
print('%s|%s' % ('yes' if '$OPENMRS/ws/fhir2/R4' in aud else 'no', c.get('preferred_username')))")"
  check "the token names this FHIR server in aud" "${claims%%|*}" "yes"
  check "the token names the expected user"       "${claims##*|}" "$DEV_USER"

  BODY="$(mktemp)"
  code="$(curl -s -o "$BODY" -w '%{http_code}' --max-time 30 \
    -H "Authorization: Bearer $ACCESS_TOKEN" "$OPENMRS/ws/fhir2/R4/Patient?_count=1" 2>/dev/null || echo 000)"
  check "the FHIR API accepts it" "$code" "200"
  got="$(python3 -c "
import json,sys
try: print(json.load(open('$BODY')).get('resourceType',''))
except Exception: print('unparseable')")"
  check "and answers with a FHIR Bundle" "$got" "Bundle"
  rm -f "$BODY"
fi

step "Result"
if [ "$FAILURES" -eq 0 ]; then
  echo "  the environment is ready for SMART launch development"
else
  echo "  $FAILURES check(s) failed"
  exit 1
fi
