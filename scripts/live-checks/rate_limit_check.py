#!/usr/bin/env python3
"""Live check for ADR-046 (per-address rate limit on the sign-in endpoints, authentication hardening Phase S2).

Usage: start the application with the limit switched on and a login capacity of 5 (dev profile otherwise):

    mvn -o -Dmaven.test.skip=true spring-boot:run "-Dspring-boot.run.arguments=--iitsm.security.rate-limit.enabled=true --iitsm.security.rate-limit.login-capacity=5"
    python scripts/live-checks/rate_limit_check.py [--base http://localhost:8085] [--no-browser] [--headed]

Every request of this script and of the browser comes from 127.0.0.1, so they share one login bucket of 5 attempts
refilled at one per 12 seconds. Run it on a freshly started application (or wait a minute between runs).

Checks:
  A. 5 logins are answered (200), the 6th is 429 ERR_RATE_LIMITED with the standard error body and Retry-After 1..12.
  B. a forged X-Forwarded-For does not give a fresh bucket (no trusted proxy configured).
  C. 10 wrong passwords while refused are all 429 and never lock the account: after waiting Retry-After, the right
     password signs in (10 counted failures would have locked it).
  D. registration has its own bucket; GET /api/auth/me is never limited.
  E. the metric iitsm.security.rate_limited counts the refusals (read as administrator).
  F. (browser, Microsoft Edge) the sign-in form shows "Too many attempts from this network. Try again in N seconds."

Exit code 0 when every check passes, 1 otherwise. Standard library only, plus Playwright for F.
"""
import argparse
import json
import sys
import time
import urllib.error
import urllib.request
import uuid

PASSWORD = "Password123!"
ADMIN = "admin@nimbusfinserve.com"
CAPACITY = 5
WINDOW_SECONDS = 60
FAILURES = []


def call(base, method, path, token=None, body=None, headers=None):
    data = None if body is None else json.dumps(body).encode("utf-8")
    request = urllib.request.Request(base + path, data=data, method=method)
    request.add_header("Content-Type", "application/json")
    for name, value in (headers or {}).items():
        request.add_header(name, value)
    if token:
        request.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            text = response.read().decode("utf-8")
            return response.status, (json.loads(text) if text else None), response.headers
    except urllib.error.HTTPError as error:
        text = error.read().decode("utf-8")
        try:
            return error.code, json.loads(text), error.headers
        except ValueError:
            return error.code, None, error.headers


def check(ok, text):
    print("%-4s %s" % ("ok" if ok else "FAIL", text))
    if not ok:
        FAILURES.append(text)


def login(base, email, password=PASSWORD, headers=None):
    return call(base, "POST", "/api/auth/login", None, {"email": email, "password": password}, headers)


def wait_for(headers):
    seconds = int(headers.get("Retry-After", "1"))
    print("     waiting %d s (Retry-After)" % seconds)
    time.sleep(seconds + 0.3)


def check_burst(base, email):
    """A: the 6th login is refused (the administrator's login was the 1st)."""
    statuses = [login(base, email)[0] for _ in range(CAPACITY - 1)]
    check(statuses == [200] * (CAPACITY - 1), "A. logins 2..5 from one address answered 200 (got %s)" % statuses)
    status, body, headers = login(base, email)
    body = body or {}
    retry = headers.get("Retry-After")
    check(status == 429, "A. 6th login is 429 (got %s)" % status)
    check(body.get("code") == "ERR_RATE_LIMITED" and body.get("status") == 429
          and "timestamp" in body and body.get("fieldErrors") == [] and "token" not in body,
          "A. error body {timestamp,status,error,code,message,fieldErrors}, no token")
    check(retry is not None and 1 <= int(retry) <= WINDOW_SECONDS // CAPACITY,
          "A. Retry-After is 1..%d seconds (got %s)" % (WINDOW_SECONDS // CAPACITY, retry))


def check_forged_header(base, email):
    status = login(base, email, headers={"X-Forwarded-For": "203.0.113.77"})[0]
    check(status == 429, "B. forged X-Forwarded-For is ignored, still 429 (got %s)" % status)


def check_no_lockout(base, email):
    statuses = [login(base, email, "Wrong-" + PASSWORD)[0] for _ in range(10)]
    check(statuses == [429] * 10, "C. 10 wrong passwords while refused are all 429")
    status, _, headers = login(base, email)
    if status == 429:
        wait_for(headers)
    status = login(base, email)[0]
    check(status == 200, "C. after Retry-After the right password signs in: account not locked (got %s)" % status)


def check_other_routes(base, token):
    status = call(base, "POST", "/api/auth/register", None, {"name": "Rate Check", "department": "Engineering",
                  "email": "rate.%s@nimbusfinserve.com" % uuid.uuid4().hex[:8], "password": PASSWORD})[0]
    check(status == 200, "D. registration has its own bucket (got %s)" % status)
    statuses = {call(base, "GET", "/api/auth/me", token)[0] for _ in range(20)}
    check(statuses == {200}, "D. 20 x GET /api/auth/me never limited (got %s)" % sorted(statuses))


def check_metric(base, admin_token, at_least):
    status, body, _ = call(base, "GET", "/actuator/metrics/iitsm.security.rate_limited", admin_token)
    count = (body or {}).get("measurements", [{}])[0].get("value", 0)
    check(status == 200 and count >= at_least, "E. metric counts the refusals (%s >= %d)" % (count, at_least))


def check_browser(base, headed):
    from playwright.sync_api import sync_playwright
    errors = []
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(channel="msedge", headless=not headed)
        page = browser.new_page()
        page.on("console", lambda m: errors.append(m.text) if m.type == "error" else None)
        page.goto(base + "/")
        for _ in range(CAPACITY + 1):
            page.fill("#login-email", ADMIN)
            page.fill("#login-password", "Wrong-" + PASSWORD)
            page.click("#btn-login-submit")
            page.wait_for_timeout(400)
        text = page.inner_text("#login-error-alert")
        check("Too many attempts from this network. Try again in " in text and "seconds" in text,
              "F. browser shows the wait from Retry-After (%r)" % text.strip().splitlines()[-1:])
        unexpected = [e for e in errors if not any(code in e for code in ("429", "401", "404"))]  # 404 = /favicon.ico (known)
        check(not unexpected, "F. no console error other than the 401/429 responses and the known favicon 404 (%s)" % unexpected)
        browser.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://localhost:8085")
    parser.add_argument("--no-browser", action="store_true")
    parser.add_argument("--headed", action="store_true")
    args = parser.parse_args()

    status, body, _ = login(args.base, ADMIN)
    if status != 200:
        sys.exit("FAIL: administrator cannot sign in (HTTP %s); start a fresh application with the limit on" % status)
    admin_token = body["token"]
    check_burst(args.base, ADMIN)
    check_forged_header(args.base, ADMIN)
    check_no_lockout(args.base, ADMIN)
    check_other_routes(args.base, admin_token)
    check_metric(args.base, admin_token, 12)
    if not args.no_browser:
        check_browser(args.base, args.headed)

    print("\n%d failure(s)" % len(FAILURES))
    sys.exit(1 if FAILURES else 0)


if __name__ == "__main__":
    main()
