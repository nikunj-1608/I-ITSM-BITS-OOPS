#!/usr/bin/env python3
"""Live check for ADR-048 (optional TOTP two-step verification for administrators, authentication hardening Phase S4).

Usage: start the application in its default dev setup (rate limit off) and run:

    mvn -o -Dmaven.test.skip=true spring-boot:run
    python scripts/live-checks/mfa_check.py [--base http://localhost:8085]

The script computes the one-time codes itself (RFC 6238, HMAC-SHA-1, 6 digits, 30 s; standard library only) and uses the
seeded IT manager (manager@nimbusfinserve.com, an administrator) as the account under test and the seeded system
administrator to unlock and reset it. Two-step verification is switched off for the manager again at the end, also when a
check fails.

Checks:
  A. employee, technician and team lead get 403 on setup/enable/disable; no token gets 401.
  B. setup returns the secret and the otpauth link, enabling with the first code returns 8 recovery codes.
  C. the password step then returns mfaRequired and a challenge, and NO access token and NO refresh token.
  D. a wrong password is the same generic 401 as before.
  E. the right code returns working tokens; the same code again is refused (replay).
  F. a recovery code works once.
  G. the challenge is rejected as a Bearer token, and an access token is rejected as a challenge.
  H. five wrong codes lock the account; an administrator unlocks it.
  I. turning it off needs the password and a valid code.
  J. another administrator can reset it; resetting oneself is refused.

Exit code 0 when every check passes, 1 otherwise.
"""
import argparse
import base64
import hashlib
import hmac
import json
import struct
import sys
import time
import urllib.error
import urllib.request

PASSWORD = "Password123!"
ADMIN = "admin@nimbusfinserve.com"
MANAGER = "manager@nimbusfinserve.com"
STEP_SECONDS = 30
FAILURES = []


def call(base, method, path, token=None, body=None, raw=None):
    data = raw.encode("utf-8") if raw is not None else (None if body is None else json.dumps(body).encode("utf-8"))
    request = urllib.request.Request(base + path, data=data, method=method)
    request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            text = response.read().decode("utf-8")
            return response.status, (json.loads(text) if text else None)
    except urllib.error.HTTPError as error:
        text = error.read().decode("utf-8")
        try:
            return error.code, json.loads(text)
        except ValueError:
            return error.code, None


def check(ok, text):
    print("%-4s %s" % ("ok" if ok else "FAIL", text))
    if not ok:
        FAILURES.append(text)


def totp(secret_text, step):
    key = base64.b32decode(secret_text + "=" * (-len(secret_text) % 8))
    digest = hmac.new(key, struct.pack(">Q", step), hashlib.sha1).digest()
    offset = digest[-1] & 0x0F
    value = struct.unpack(">I", digest[offset:offset + 4])[0] & 0x7FFFFFFF
    return "%06d" % (value % 1_000_000)


class Codes:
    """Hands out codes for strictly increasing time steps (a step is accepted once), waiting when the window runs out."""

    def __init__(self, secret):
        self.secret = secret
        self.last = int(time.time()) // STEP_SECONDS - 2

    def next(self):
        step = self.last + 1
        while step > int(time.time()) // STEP_SECONDS + 1:
            time.sleep(1)
        self.last = step
        return totp(self.secret, step)

    def wrong(self):
        valid = {totp(self.secret, s) for s in range(int(time.time()) // STEP_SECONDS - 3, int(time.time()) // STEP_SECONDS + 4)}
        return next("%06d" % n for n in range(1_000_000) if "%06d" % n not in valid)


def login(base, email, password=PASSWORD):
    return call(base, "POST", "/api/auth/login", None, {"email": email, "password": password})


def token_of(base, email):
    status, body = login(base, email)
    if status != 200 or not body.get("token"):
        sys.exit("FAIL: cannot sign in as %s (HTTP %s); is two-step verification left on from an earlier run?" % (email, status))
    return body["token"]


def verify(base, challenge, code):
    return call(base, "POST", "/api/auth/mfa/verify", None, {"mfaToken": challenge, "code": code})


def check_access_rules(base):
    for email in ("employee@nimbusfinserve.com", "tech.network@nimbusfinserve.com", "lead.network@nimbusfinserve.com"):
        token = token_of(base, email)
        statuses = [call(base, "POST", "/api/auth/mfa/setup", token, {})[0],
                    call(base, "POST", "/api/auth/mfa/enable", token, {"code": "123456"})[0],
                    call(base, "POST", "/api/auth/mfa/disable", token, {"password": PASSWORD, "code": "123456"})[0]]
        check(statuses == [403, 403, 403], "A. %s gets 403 on setup, enable and disable (got %s)" % (email, statuses))
    statuses = [call(base, "POST", "/api/auth/mfa/setup", None, {})[0], call(base, "POST", "/api/auth/mfa/enable", None, {"code": "123456"})[0]]
    check(statuses == [401, 401], "A. no token gets 401 (got %s)" % statuses)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://localhost:8085")
    base = parser.parse_args().base
    admin_token = token_of(base, ADMIN)
    manager_token = token_of(base, MANAGER)
    manager_id = call(base, "GET", "/api/auth/me", manager_token)[1]["userId"]
    admin_id = call(base, "GET", "/api/auth/me", admin_token)[1]["userId"]
    enrolled = False
    try:
        check_access_rules(base)

        status, setup = call(base, "POST", "/api/auth/mfa/setup", manager_token, {})
        secret = (setup or {}).get("secret", "")
        check(status == 200 and len(secret) == 32 and (setup["otpauthUri"] or "").startswith("otpauth://totp/")
              and secret in setup["otpauthUri"], "B. setup returns a 32-character secret and the otpauth link")
        codes = Codes(secret)
        status, enabled = call(base, "POST", "/api/auth/mfa/enable", manager_token, {"code": codes.next()})
        enrolled = status == 200
        recovery = (enabled or {}).get("recoveryCodes", [])
        check(status == 200 and len(recovery) == 8, "B. enabling with the first code returns 8 recovery codes (HTTP %s)" % status)
        check(call(base, "GET", "/api/auth/me", manager_token)[1].get("mfaEnabled") is True, "B. /me says two-step verification is on")

        status, body = login(base, MANAGER)
        challenge = (body or {}).get("mfaToken")
        check(status == 200 and body.get("mfaRequired") is True and challenge and not body.get("token")
              and not body.get("refreshToken"), "C. the password step returns mfaRequired, a challenge and no tokens")
        status, body = login(base, MANAGER, "Wrong-" + PASSWORD)
        check(status == 401 and "mfa" not in json.dumps(body).lower(), "D. a wrong password is the generic 401 with no MFA hint")

        code = codes.next()
        status, session = verify(base, challenge, code)
        check(status == 200 and session.get("token") and session.get("refreshToken"), "E. the right code returns tokens")
        check(call(base, "GET", "/api/auth/me", (session or {}).get("token"))[0] == 200, "E. the tokens work")
        status, body = verify(base, challenge, code)
        check(status == 401 and (body or {}).get("code") == "ERR_INVALID_MFA_CODE", "E. the same code again is refused (replay)")

        status, session = verify(base, challenge, recovery[0])
        check(status == 200, "F. a recovery code signs in (HTTP %s)" % status)
        check(verify(base, challenge, recovery[0])[0] == 401, "F. the same recovery code a second time is refused")
        check(verify(base, challenge, recovery[1].lower()[:5] + "-" + recovery[1].lower()[5:])[0] == 200,
              "F. another recovery code works typed in lower case with a hyphen")

        check(call(base, "GET", "/api/auth/me", challenge)[0] == 401, "G. the challenge is rejected as a Bearer token")
        access = call(base, "POST", "/api/auth/mfa/verify", None, {"mfaToken": manager_token, "code": codes.wrong()})
        check(access[0] == 401 and (access[1] or {}).get("code") == "ERR_INVALID_MFA_CODE",
              "G. an access token is rejected as a challenge")

        wrong = codes.wrong()
        statuses = [verify(base, challenge, wrong)[0] for _ in range(5)]
        check(statuses == [401] * 5, "H. five wrong codes are all 401 (got %s)" % statuses)
        check(login(base, MANAGER)[0] == 401, "H. the account is then locked: even the right password gives 401")
        check(call(base, "POST", "/api/admin/users/%s/unlock" % manager_id, admin_token, {})[0] == 200,
              "H. an administrator unlocks it")

        challenge = login(base, MANAGER)[1]["mfaToken"]
        live = verify(base, challenge, codes.next())[1]["token"]
        bad_password = call(base, "POST", "/api/auth/mfa/disable", live, {"password": "wrong", "code": codes.wrong()})
        check(bad_password[0] == 401 and bad_password[1].get("code") == "ERR_INVALID_CREDENTIALS",
              "I. turning it off with a wrong password is refused")
        bad_code = call(base, "POST", "/api/auth/mfa/disable", live, {"password": PASSWORD, "code": codes.wrong()})
        check(bad_code[0] == 401 and bad_code[1].get("code") == "ERR_INVALID_MFA_CODE", "I. turning it off with a wrong code is refused")
        status = call(base, "POST", "/api/auth/mfa/disable", live, {"password": PASSWORD, "code": codes.next()})[0]
        check(status == 204, "I. the password and a valid code turn it off (HTTP %s)" % status)
        enrolled = status != 204
        check(login(base, MANAGER)[1].get("token") is not None, "I. sign-in needs no code afterwards")

        manager_token = token_of(base, MANAGER)
        setup = call(base, "POST", "/api/auth/mfa/setup", manager_token, {})[1]
        codes = Codes(setup["secret"])
        enrolled = call(base, "POST", "/api/auth/mfa/enable", manager_token, {"code": codes.next()})[0] == 200
        status = call(base, "POST", "/api/admin/users/%s/mfa/reset" % manager_id, admin_token, {})[0]
        check(status == 204, "J. another administrator resets it (HTTP %s)" % status)
        enrolled = status != 204
        check(call(base, "GET", "/api/auth/me", manager_token)[0] == 401, "J. the reset ended the target's sessions")
        check(login(base, MANAGER)[1].get("token") is not None, "J. the account signs in without a code afterwards")
        check(call(base, "POST", "/api/admin/users/%s/mfa/reset" % admin_id, admin_token, {})[0] == 400,
              "J. resetting oneself is refused")
    finally:
        if enrolled:
            call(base, "POST", "/api/admin/users/%s/mfa/reset" % manager_id, admin_token, {})
            call(base, "POST", "/api/admin/users/%s/unlock" % manager_id, admin_token, {})
    print("\n%d failure(s)" % len(FAILURES))
    sys.exit(1 if FAILURES else 0)


if __name__ == "__main__":
    main()
