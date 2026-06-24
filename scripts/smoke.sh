#!/usr/bin/env bash
# =====================================================================
# scripts/smoke.sh — end-to-end happy-path sanity check for the
# Complaints backend (dev profile, console SMS, local image storage).
#
# Drives the full lifecycle in one shot so a maintainer can answer
# "did I just break the wire contract?" in ~20s instead of click-
# walking Swagger.
#
#   admin login + first-time password change
#   admin creates (or reuses) an engineer + a technician in DC-NSK-007
#   engineer + technician clear their password-reset flags
#   consumer OTP send → verify → 5-min consumer JWT
#   consumer submits a complaint
#   engineer assigns to the technician
#   technician starts → resolves
#   engineer closes
#   consumer reads detail (feedbackSubmitted=false) → submits feedback
#   consumer reads feedback back (200, non-null body)
#
# Prereqs (already in place per docs/ENVIRONMENT_SETUP.md):
#   - Postgres running (docker compose up -d)
#   - BE running with profile=dev (./mvnw spring-boot:run -Dspring-boot.run.profiles=dev)
#   - jq + curl installed
#
# OTP retrieval:
#   - If APP_LOG_FILE is set, we tail it for the most recent
#     [DEV-SMS] line. Easiest: launch the BE with
#       ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev \
#         > /tmp/complaints.log 2>&1 &
#       APP_LOG_FILE=/tmp/complaints.log ./scripts/smoke.sh
#   - Otherwise we prompt interactively. Paste the 6-digit OTP from
#     the BE terminal when asked.
#
# Override any default via env: BASE, ADMIN_EMPLOYEE_ID, ADMIN_PASSWORD,
# ENG_PASSWORD, TECH_PASSWORD, CONSUMER_ID, CONSUMER_MOBILE, CATEGORY_CODE.
# (ENG_PASSWORD / TECH_PASSWORD are the *post-reset* passwords the staff choose
# on first login. The initial temp password is always server-generated.)
# =====================================================================
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
ADMIN_EMPLOYEE_ID="${ADMIN_EMPLOYEE_ID:-ADMIN001}"
ADMIN_PASSWORD_INITIAL="${ADMIN_PASSWORD_INITIAL:-ChangeMe!123}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-Smoke!Admin1}"
ENG_EMPLOYEE_ID="${ENG_EMPLOYEE_ID:-SMOKE-ENG-007}"
TECH_EMPLOYEE_ID="${TECH_EMPLOYEE_ID:-SMOKE-TECH-007}"
# Passwords the engineer/technician will use AFTER the forced reset.
# The initial temp password is server-generated and captured from the reset-password response.
ENG_PASSWORD="${ENG_PASSWORD:-Smoke!Eng2}"
TECH_PASSWORD="${TECH_PASSWORD:-Smoke!Tech2}"
CONSUMER_ID="${CONSUMER_ID:-MH00010001}"
CONSUMER_MOBILE="${CONSUMER_MOBILE:-+919900000001}"
CATEGORY_CODE="${CATEGORY_CODE:-POWER_OUTAGE}"
DC_CODE="${DC_CODE:-DC-NSK-007}"

# Colour helpers --------------------------------------------------------
R='\033[0;31m' ; G='\033[0;32m' ; Y='\033[0;33m' ; B='\033[0;34m' ; D='\033[0m'
STEP=0
step()  { STEP=$((STEP+1)); printf "\n${B}[%02d] %s${D}\n" "$STEP" "$*"; }
ok()    { printf "     ${G}✓ %s${D}\n" "$*"; }
fail()  { printf "     ${R}✗ %s${D}\n" "$*" >&2; exit 1; }
info()  { printf "     ${Y}→ %s${D}\n" "$*"; }

# Tooling check ---------------------------------------------------------
for bin in curl jq; do
    command -v "$bin" >/dev/null || fail "missing dependency: $bin"
done

TMP=$(mktemp -d -t complaints-smoke.XXXXXX)
trap 'rm -rf "$TMP"' EXIT
RESP="$TMP/resp.json"

# req METHOD PATH [BEARER] [BODY|@FILE] [CTYPE]
# Writes response body to $RESP, prints HTTP status to stdout via stderr.
req() {
    local method="$1" path="$2" bearer="${3:-}" body="${4:-}" ctype="${5:-application/json}"
    local args=(-sS -X "$method" -H 'Accept: application/json' -w '\n%{http_code}' -o "$RESP.body")
    [[ -n "$bearer" ]] && args+=(-H "Authorization: Bearer $bearer")
    if [[ -n "$body" ]]; then
        args+=(-H "Content-Type: $ctype")
        if [[ "$body" == @* ]]; then args+=(--data-binary "$body"); else args+=(--data "$body"); fi
    fi
    local code
    code=$(curl "${args[@]}" "$BASE$path" | tail -1)
    cp "$RESP.body" "$RESP"
    echo "$code"
}

# expect HTTP_OK/2xx; on mismatch dump payload and abort
expect2xx() {
    local code="$1" what="$2"
    if [[ ! "$code" =~ ^2 ]]; then
        printf "${R}HTTP %s on %s${D}\n" "$code" "$what" >&2
        cat "$RESP" >&2 ; echo >&2
        fail "$what"
    fi
    jq -e '.success == true' "$RESP" >/dev/null \
        || fail "$what: envelope.success != true"
}

# expect a 4xx (used for idempotent re-create paths)
expect4xx() {
    local code="$1" what="$2"
    [[ "$code" =~ ^4 ]] || fail "$what: expected 4xx, got $code"
}

# Fetch OTP from the app log (or prompt the user)
get_otp() {
    if [[ -n "${APP_LOG_FILE:-}" && -r "$APP_LOG_FILE" ]]; then
        # Wait up to 5s for the OTP line to appear.
        for _ in {1..10}; do
            local line
            line=$(grep -a "\[DEV-SMS\] mobile=$CONSUMER_MOBILE" "$APP_LOG_FILE" | tail -1 || true)
            if [[ -n "$line" ]]; then
                echo "$line" | sed -E 's/.* otp=([0-9]+).*/\1/'
                return 0
            fi
            sleep 0.5
        done
        fail "no [DEV-SMS] line in $APP_LOG_FILE for $CONSUMER_MOBILE within 5s"
    fi
    printf "     ${Y}OTP prompt — paste the 6-digit code from the BE terminal: ${D}"
    read -r otp
    echo "$otp"
}

# =====================================================================
# 0 — Liveness
# =====================================================================
step "Liveness — GET /v3/api-docs"
code=$(req GET /v3/api-docs)
[[ "$code" == "200" ]] || fail "BE not reachable at $BASE (got $code)"
ok "BE up at $BASE"

# =====================================================================
# 1 — Admin login (handle first-boot password-reset flag)
# =====================================================================
step "Admin login as $ADMIN_EMPLOYEE_ID"
code=$(req POST /api/v1/staff/auth/login '' \
    "{\"employeeId\":\"$ADMIN_EMPLOYEE_ID\",\"password\":\"$ADMIN_PASSWORD\"}")
if [[ "$code" != "200" ]]; then
    info "default password may still be in force — retrying with ADMIN_PASSWORD_INITIAL"
    code=$(req POST /api/v1/staff/auth/login '' \
        "{\"employeeId\":\"$ADMIN_EMPLOYEE_ID\",\"password\":\"$ADMIN_PASSWORD_INITIAL\"}")
    expect2xx "$code" "admin login (initial)"
    ADMIN_TOKEN=$(jq -r '.data.accessToken' "$RESP")
    info "clearing admin password-reset flag"
    code=$(req POST /api/v1/staff/auth/change-password "$ADMIN_TOKEN" \
        "{\"currentPassword\":\"$ADMIN_PASSWORD_INITIAL\",\"newPassword\":\"$ADMIN_PASSWORD\"}")
    expect2xx "$code" "admin change-password"
    code=$(req POST /api/v1/staff/auth/login '' \
        "{\"employeeId\":\"$ADMIN_EMPLOYEE_ID\",\"password\":\"$ADMIN_PASSWORD\"}")
fi
expect2xx "$code" "admin login"
ADMIN_TOKEN=$(jq -r '.data.accessToken' "$RESP")
ok "admin token acquired"

# =====================================================================
# 2 — Resolve subdivision + DC + category IDs
# =====================================================================
step "Resolve master-data IDs"
code=$(req GET "/api/v1/staff/masterdata/distribution-centers?size=200" "$ADMIN_TOKEN")
expect2xx "$code" "list DCs"
DC_ID=$(jq -r ".data.content[] | select(.code==\"$DC_CODE\") | .id" "$RESP")
SUB_ID=$(jq -r ".data.content[] | select(.code==\"$DC_CODE\") | .subdivisionId" "$RESP")
[[ -n "$DC_ID" && "$DC_ID" != "null" ]] || fail "DC $DC_CODE not found"
ok "DC $DC_CODE = id $DC_ID (subdivision $SUB_ID)"

code=$(req GET "/api/v1/staff/masterdata/categories?size=200" "$ADMIN_TOKEN")
expect2xx "$code" "list categories"
CATEGORY_ID=$(jq -r ".data.content[] | select(.code==\"$CATEGORY_CODE\") | .id" "$RESP")
[[ -n "$CATEGORY_ID" && "$CATEGORY_ID" != "null" ]] || fail "category $CATEGORY_CODE not found"
ok "category $CATEGORY_CODE = id $CATEGORY_ID"

# =====================================================================
# 3 — Create (or reuse) engineer + technician
# =====================================================================
create_staff() {
    local empId="$1" role="$2" fullName="$3"
    local body
    body=$(jq -nc \
        --arg id "$empId" --arg name "$fullName" --arg role "$role" \
        --argjson sub "$SUB_ID" --argjson dc "$DC_ID" \
        '{employeeId:$id, fullName:$name, role:$role,
          email:($id|ascii_downcase + "@example.in"),
          mobile:"+919000000000",
          subdivisionId:$sub, distributionCenterId:$dc}')
    local code
    code=$(req POST /api/v1/admin/staff "$ADMIN_TOKEN" "$body")
    if [[ "$code" =~ ^2 ]]; then
        info "$role $empId created"
    elif [[ "$code" == "409" ]]; then
        info "$role $empId already exists — reusing"
    else
        printf "${R}create-staff returned %s${D}\n" "$code" >&2; cat "$RESP" >&2
        fail "create $role"
    fi
}
# reset_password <employeeId>
# The reset-password endpoint generates a random 16-char temp password server-side
# and returns it as data.temporaryPassword — no request body is accepted.
# Echoes "userId:temporaryPassword" so the caller can split both values.
reset_password() {
    local empId="$1"
    # Fetch the full staff list (scoped to admin's subdivision); filter client-side.
    # employeeId is not an API filter param — we jq-select after the fact.
    code=$(req GET "/api/v1/admin/staff?size=200" "$ADMIN_TOKEN")
    expect2xx "$code" "lookup $empId"
    local userId
    userId=$(jq -r ".data.content[]? | select(.employeeId==\"$empId\") | .id" "$RESP" | head -1)
    [[ -n "$userId" ]] || fail "$empId not found in staff list"
    # No request body — server generates the temp password.
    code=$(req POST "/api/v1/admin/staff/$userId/reset-password" "$ADMIN_TOKEN")
    expect2xx "$code" "reset password for $empId"
    local tempPwd
    tempPwd=$(jq -r '.data.temporaryPassword' "$RESP")
    [[ -n "$tempPwd" && "$tempPwd" != "null" ]] || fail "no temporaryPassword in reset-password response for $empId"
    echo "$userId:$tempPwd"
}

step "Create engineer + technician (idempotent)"
create_staff "$ENG_EMPLOYEE_ID"  "ENGINEER"   "Smoke Engineer"
create_staff "$TECH_EMPLOYEE_ID" "TECHNICIAN" "Smoke Technician"

# reset-password returns "userId:temporaryPassword" — split on the first colon.
ENG_RESET=$(reset_password "$ENG_EMPLOYEE_ID")
ENG_USER_ID="${ENG_RESET%%:*}"
ENG_TEMP_PWD="${ENG_RESET#*:}"

TECH_RESET=$(reset_password "$TECH_EMPLOYEE_ID")
TECH_USER_ID="${TECH_RESET%%:*}"
TECH_TEMP_PWD="${TECH_RESET#*:}"

ok "engineer userId=$ENG_USER_ID, technician userId=$TECH_USER_ID"

# =====================================================================
# 4 — Engineer + technician first-login + change-password to clear flag
# =====================================================================
login_and_clear_reset() {
    local empId="$1" oldPwd="$2" newPwd="$3" label="$4"
    code=$(req POST /api/v1/staff/auth/login '' \
        "{\"employeeId\":\"$empId\",\"password\":\"$oldPwd\"}")
    expect2xx "$code" "$label initial login"
    local tok
    tok=$(jq -r '.data.accessToken' "$RESP")
    code=$(req POST /api/v1/staff/auth/change-password "$tok" \
        "{\"currentPassword\":\"$oldPwd\",\"newPassword\":\"$newPwd\"}")
    expect2xx "$code" "$label change-password"
    code=$(req POST /api/v1/staff/auth/login '' \
        "{\"employeeId\":\"$empId\",\"password\":\"$newPwd\"}")
    expect2xx "$code" "$label login (post-change)"
    jq -r '.data.accessToken' "$RESP"
}

step "Engineer first-login + change-password"
ENG_TOKEN=$(login_and_clear_reset "$ENG_EMPLOYEE_ID"  "$ENG_TEMP_PWD"  "$ENG_PASSWORD"  "engineer")
ok "engineer token acquired"

step "Technician first-login + change-password"
TECH_TOKEN=$(login_and_clear_reset "$TECH_EMPLOYEE_ID" "$TECH_TEMP_PWD" "$TECH_PASSWORD" "technician")
ok "technician token acquired"

# =====================================================================
# 5 — Consumer OTP send + verify
# =====================================================================
step "Consumer OTP send for $CONSUMER_ID"
code=$(req POST /api/v1/auth/consumer/otp/send '' \
    "{\"consumerId\":\"$CONSUMER_ID\",\"mobile\":\"$CONSUMER_MOBILE\"}")
expect2xx "$code" "OTP send"
ok "OTP issued"

step "Read OTP from app log (or prompt)"
OTP=$(get_otp)
[[ "$OTP" =~ ^[0-9]{6}$ ]] || fail "OTP not 6 digits: '$OTP'"
ok "OTP retrieved"

step "Consumer OTP verify"
code=$(req POST /api/v1/auth/consumer/otp/verify '' \
    "{\"consumerId\":\"$CONSUMER_ID\",\"mobile\":\"$CONSUMER_MOBILE\",\"otp\":\"$OTP\"}")
expect2xx "$code" "OTP verify"
CONSUMER_TOKEN=$(jq -r '.data.verificationToken' "$RESP")
ok "consumer verification token acquired"

# =====================================================================
# 6 — Consumer submits a complaint (multipart, no images)
# =====================================================================
step "Consumer submit complaint (no images)"
COMPLAINT_JSON=$(jq -nc \
    --arg cid "$CONSUMER_ID" --arg mob "$CONSUMER_MOBILE" \
    --argjson cat "$CATEGORY_ID" \
    '{consumerId:$cid, mobile:$mob, categoryId:$cat,
      description:"Smoke test - power gone since morning",
      location:"Plot 17, Sinnar"}')
http_code=$(curl -sS -X POST "$BASE/api/v1/consumer/complaints" \
    -H "Authorization: Bearer $CONSUMER_TOKEN" \
    -H 'Accept: application/json' \
    -F "complaint=$COMPLAINT_JSON;type=application/json" \
    -w '\n%{http_code}' -o "$RESP" | tail -1)
expect2xx "$http_code" "submit complaint"
TICKET_NO=$(jq -r '.data.ticketNo' "$RESP")
COMPLAINT_ID=$(jq -r '.data.complaintId' "$RESP")
ok "ticket=$TICKET_NO id=$COMPLAINT_ID"

# =====================================================================
# 7 — Engineer assigns to technician
# =====================================================================
step "Engineer assigns complaint $COMPLAINT_ID to technician"
code=$(req POST "/api/v1/staff/complaints/$COMPLAINT_ID/assign" "$ENG_TOKEN" \
    "{\"technicianId\":$TECH_USER_ID,\"severity\":\"MEDIUM\"}")
expect2xx "$code" "assign"
ok "assigned"

# =====================================================================
# 8 — Technician starts → resolves
# =====================================================================
step "Technician starts"
code=$(req POST "/api/v1/technician/complaints/$COMPLAINT_ID/start" "$TECH_TOKEN" '')
expect2xx "$code" "start"
ok "in progress"

step "Technician resolves"
code=$(req POST "/api/v1/technician/complaints/$COMPLAINT_ID/resolve" "$TECH_TOKEN" \
    '{"resolutionNotes":"Restored line - loose joint at pole 4"}')
expect2xx "$code" "resolve"
ok "resolved"

# =====================================================================
# 9 — Engineer closes
# =====================================================================
step "Engineer closes"
code=$(req POST "/api/v1/staff/complaints/$COMPLAINT_ID/close" "$ENG_TOKEN" '{}')
expect2xx "$code" "close"
ok "closed"

# =====================================================================
# 10 — Consumer reads detail + history
# =====================================================================
step "Consumer GETs detail by ticketNo"
code=$(req GET "/api/v1/consumer/complaints/$TICKET_NO" "$CONSUMER_TOKEN")
expect2xx "$code" "consumer get detail"
STATUS=$(jq -r '.data.status' "$RESP")
FEEDBACK_SUBMITTED=$(jq -r '.data.feedbackSubmitted' "$RESP")
[[ "$STATUS" == "CLOSED" ]] || fail "expected CLOSED, got $STATUS"
[[ "$FEEDBACK_SUBMITTED" == "false" ]] || fail "feedbackSubmitted should be false pre-submit"
ok "status=CLOSED, feedbackSubmitted=false"

step "Consumer GETs history (Stage 17 consumer-safe shape)"
code=$(req GET "/api/v1/consumer/complaints/$TICKET_NO/history" "$CONSUMER_TOKEN")
expect2xx "$code" "consumer get history"
HISTORY_COUNT=$(jq '.data | length' "$RESP")
ok "history rows: $HISTORY_COUNT"

# =====================================================================
# 11 — Consumer submits feedback + read-back
# =====================================================================
step "Consumer submits feedback (rating=5)"
code=$(req POST "/api/v1/consumer/complaints/$TICKET_NO/feedback" "$CONSUMER_TOKEN" \
    '{"rating":5,"comment":"Quick fix, thank you"}')
expect2xx "$code" "submit feedback"
FB_ID=$(jq -r '.data.id' "$RESP")
ok "feedback id=$FB_ID"

step "Consumer GETs feedback read-back (Stage 20.1)"
code=$(req GET "/api/v1/consumer/complaints/$TICKET_NO/feedback" "$CONSUMER_TOKEN")
expect2xx "$code" "get feedback"
RATING=$(jq -r '.data.rating' "$RESP")
[[ "$RATING" == "5" ]] || fail "expected rating=5, got $RATING"
ok "rating=5 retrieved"

step "Consumer GETs detail again — feedbackSubmitted should flip to true"
code=$(req GET "/api/v1/consumer/complaints/$TICKET_NO" "$CONSUMER_TOKEN")
expect2xx "$code" "consumer get detail (post-feedback)"
FEEDBACK_SUBMITTED=$(jq -r '.data.feedbackSubmitted' "$RESP")
[[ "$FEEDBACK_SUBMITTED" == "true" ]] || fail "feedbackSubmitted should be true after submit"
ok "feedbackSubmitted=true"

# =====================================================================
# 12 — Consumer tracking list — verify the ticket + feedbackSubmitted hint
# =====================================================================
step "Consumer tracking list shows the ticket with feedbackSubmitted=true"
code=$(req GET "/api/v1/consumer/complaints?size=50" "$CONSUMER_TOKEN")
expect2xx "$code" "consumer list"
ROW_FB=$(jq -r ".data.content[] | select(.ticketNo==\"$TICKET_NO\") | .feedbackSubmitted" "$RESP")
[[ "$ROW_FB" == "true" ]] || fail "list row feedbackSubmitted should be true, got $ROW_FB"
ok "row feedbackSubmitted=true"

# =====================================================================
printf "\n${G}========================================${D}\n"
printf "${G}  Smoke PASS — %d steps green${D}\n" "$STEP"
printf "${G}========================================${D}\n"
printf "  ticket: %s  id: %s  rating: 5  feedbackSubmitted: true\n" "$TICKET_NO" "$COMPLAINT_ID"

