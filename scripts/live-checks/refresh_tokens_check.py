#!/usr/bin/env python3
"""Live check for ADR-047 (sessions with a short access token and a rotating refresh token, authentication hardening Phase S3).

Usage: start the application in its default dev setup (rate limit off) and run:

    mvn -o -Dmaven.test.skip=true spring-boot:run
    python scripts/live-checks/refresh_tokens_check.py [--base http://localhost:8085]

Checks (every user is registered by the script; the simulation clock is moved for I and always reset):
  A. sign-in returns an access token, a refresh token (43 URL-safe characters) and expiresIn 900.
  B. refresh rotates: new tokens, the new access token works, the used refresh token is 401 ERR_INVALID_REFRESH_TOKEN.
  C. replaying a used refresh token revokes the session: the newer refresh token and the access tokens are 401.
  D. sign-out kills the access token at once and the refresh token; signing out again is 204.
  E. sign out everywhere ends every session of the user (and not another user's).
  F. 20 simultaneous refreshes of one token: exactly one 200, the others 401, never two new tokens.
  G. bad input is 400 or 401, never 500 (oversized, blank, garbage, broken JSON).
  I. the simulation clock moved by a day does not end a session (rule R1).

Exit code 0 when every check passes, 1 otherwise. Standard library only.
"""
import argparse
import json
import re
import sys
import threading
import urllib.error
import urllib.request
import uuid

PASSWORD = "Password123!"
ADMIN = "admin@nimbusfinserve.com"
SIMULTANEOUS = 20
ONE_DAY_MINUTES = 24 * 60
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


def new_user(base):
    email = "sess.%s@nimbusfinserve.com" % uuid.uuid4().hex[:8]
    status, _ = call(base, "POST", "/api/auth/register", None,
                     {"name": "Session Check", "email": email, "password": PASSWORD, "department": "Engineering"})
    if status != 200:
        sys.exit("FAIL: cannot register %s (HTTP %s)" % (email, status))
    return email


def login(base, email):
    status, body = call(base, "POST", "/api/auth/login", None, {"email": email, "password": PASSWORD})
    if status != 200:
        sys.exit("FAIL: cannot sign in as %s (HTTP %s)" % (email, status))
    return body


def refresh(base, refresh_token):
    return call(base, "POST", "/api/auth/refresh", None, {"refreshToken": refresh_token})


def me(base, access):
    return call(base, "GET", "/api/auth/me", access)[0]


def check_login(base):
    body = login(base, new_user(base))
    ok = (body.get("token") and re.fullmatch(r"[A-Za-z0-9_-]{43}", body.get("refreshToken") or "")
          and body.get("expiresIn") == 900)
    check(ok, "A. sign-in returns access token, 43-character refresh token and expiresIn 900")


def check_rotation(base):
    first = login(base, new_user(base))
    status, second = refresh(base, first["refreshToken"])
    check(status == 200 and second["refreshToken"] != first["refreshToken"], "B. refresh rotates the refresh token")
    check(me(base, second["token"]) == 200, "B. the new access token works")
    status, body = refresh(base, first["refreshToken"])
    check(status == 401 and (body or {}).get("code") == "ERR_INVALID_REFRESH_TOKEN",
          "B. the used refresh token is 401 ERR_INVALID_REFRESH_TOKEN (got %s)" % status)


def check_replay(base):
    first = login(base, new_user(base))
    _, second = refresh(base, first["refreshToken"])
    refresh(base, first["refreshToken"])
    check(refresh(base, second["refreshToken"])[0] == 401, "C. after a replay the newer refresh token is dead")
    check(me(base, second["token"]) == 401 and me(base, first["token"]) == 401,
          "C. after a replay every access token of the session is 401 at once")


def check_logout(base):
    session = login(base, new_user(base))
    check(me(base, session["token"]) == 200, "D. access token works before sign-out")
    status = call(base, "POST", "/api/auth/logout", None, {"refreshToken": session["refreshToken"]})[0]
    check(status == 204, "D. sign-out is 204 (got %s)" % status)
    check(me(base, session["token"]) == 401, "D. access token is 401 at once after sign-out")
    check(refresh(base, session["refreshToken"])[0] == 401, "D. refresh token is 401 after sign-out")
    again = call(base, "POST", "/api/auth/logout", None, {"refreshToken": session["refreshToken"]})[0]
    check(again == 204, "D. signing out again is still 204 (got %s)" % again)


def check_logout_all(base):
    email = new_user(base)
    a, b = login(base, email), login(base, email)
    other = login(base, new_user(base))
    status = call(base, "POST", "/api/auth/logout-all", a["token"])[0]
    check(status == 204, "E. sign out everywhere is 204 (got %s)" % status)
    check(refresh(base, b["refreshToken"])[0] == 401 and me(base, b["token"]) == 401,
          "E. the user's other session is dead")
    check(me(base, a["token"]) == 401, "E. the calling session is dead too")
    check(me(base, other["token"]) == 200, "E. another user's session is untouched")
    check(call(base, "POST", "/api/auth/logout-all")[0] == 401, "E. sign out everywhere without a token is 401")


def check_simultaneous(base):
    session = login(base, new_user(base))
    results = []
    barrier = threading.Barrier(SIMULTANEOUS)

    def go():
        barrier.wait()
        results.append(refresh(base, session["refreshToken"])[0])

    threads = [threading.Thread(target=go) for _ in range(SIMULTANEOUS)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    check(results.count(200) == 1 and results.count(401) == SIMULTANEOUS - 1,
          "F. %d simultaneous refreshes of one token: one 200, the rest 401 (got %d x 200, %d x 401, others %s)" % (
              SIMULTANEOUS, results.count(200), results.count(401), sorted(set(results) - {200, 401})))


def check_bad_input(base):
    cases = [("oversized", {"refreshToken": "x" * 201}, 400), ("blank", {"refreshToken": "  "}, 400),
             ("missing", {}, 400), ("garbage", {"refreshToken": "é中-garbage"}, 401),
             ("never issued", {"refreshToken": "x" * 43}, 401)]
    for name, body, expected in cases:
        status = call(base, "POST", "/api/auth/refresh", None, body)[0]
        check(status == expected, "G. %s refresh token is %d (got %s)" % (name, expected, status))
    status = call(base, "POST", "/api/auth/refresh", None, None, raw="not json")[0]
    check(status == 400, "G. broken JSON is 400 (got %s)" % status)


def check_simulation_clock(base):
    admin = login(base, ADMIN)
    session = login(base, new_user(base))
    try:
        status = call(base, "POST", "/api/simulation/advance?minutes=%d" % ONE_DAY_MINUTES, admin["token"])[0]
        check(status == 200, "I. simulation advanced one day (got %s)" % status)
        check(me(base, session["token"]) == 200, "I. the access token still works after the simulated day")
        check(refresh(base, session["refreshToken"])[0] == 200, "I. the refresh token still works after the simulated day")
    finally:
        call(base, "POST", "/api/simulation/reset", admin["token"])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://localhost:8085")
    base = parser.parse_args().base
    for step in (check_login, check_rotation, check_replay, check_logout, check_logout_all, check_simultaneous,
                 check_bad_input, check_simulation_clock):
        step(base)
    print("\n%d failure(s)" % len(FAILURES))
    sys.exit(1 if FAILURES else 0)


if __name__ == "__main__":
    main()
