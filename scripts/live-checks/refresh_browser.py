#!/usr/bin/env python3
"""Real-browser check for ADR-047 (silent session renewal, authentication hardening Phase S3), in Microsoft Edge.

Usage: start the application with an access token that lives 5 seconds, then run (Python with Playwright and Edge):

    mvn -o -Dmaven.test.skip=true spring-boot:run "-Dspring-boot.run.arguments=--iitsm.security.jwt.expiration-ms=5000"
    python scripts/live-checks/refresh_browser.py [--base http://localhost:8085] [--headed]

Every step signs in through the real login form.
  A. sign in, wait 6 s (the access token has expired), use the app: still signed in, the stored tokens changed.
  B. wait again and fire two API calls at the same moment: both succeed and exactly ONE /api/auth/refresh request is sent
     (the single-flight guard; without it the second request would replay the used token and end the session).
  C. sign out sends POST /api/auth/logout, and the refresh token that was in use is dead afterwards (401).
  D. no console error other than the 401 answers to the expired token and the known /favicon.ico 404.

Exit code 0 when every check passes, 1 otherwise.
"""
import argparse
import json
import sys
import time
import urllib.error
import urllib.request

from playwright.sync_api import sync_playwright

PASSWORD = "Password123!"
EMPLOYEE = "employee@nimbusfinserve.com"
EXPIRY_WAIT_MS = 6500
FAILURES = []


def check(ok, text):
    print("%-4s %s" % ("ok" if ok else "FAIL", text))
    if not ok:
        FAILURES.append(text)


def post(base, path, body):
    request = urllib.request.Request(base + path, data=json.dumps(body).encode("utf-8"), method="POST")
    request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status
    except urllib.error.HTTPError as error:
        return error.code


def stored_session(page):
    return page.evaluate("JSON.parse(sessionStorage.getItem('iitsm_session') || 'null')")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://localhost:8085")
    parser.add_argument("--headed", action="store_true")
    args = parser.parse_args()

    requests, console_errors = [], []
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(channel="msedge", headless=not args.headed)
        page = browser.new_page()
        page.on("request", lambda r: requests.append((r.method, r.url)))
        page.on("console", lambda m: console_errors.append(m.text) if m.type == "error" else None)

        page.goto(args.base + "/")
        page.fill("#login-email", EMPLOYEE)
        page.fill("#login-password", PASSWORD)
        page.click("#form-login button[type=submit]")
        page.wait_for_selector("#btn-logout", timeout=15000)
        first = stored_session(page)
        check(first and first.get("refreshToken"), "A. sign-in keeps a refresh token in the session")

        page.wait_for_timeout(EXPIRY_WAIT_MS)
        page.evaluate("apiCall('/api/employee/incidents')")
        page.wait_for_timeout(1500)
        second = stored_session(page)
        check(page.locator("#btn-logout").is_visible(), "A. after the access token expired the user is still signed in")
        check(second and second["token"] != first["token"] and second["refreshToken"] != first["refreshToken"],
              "A. the stored access and refresh tokens were renewed")

        page.wait_for_timeout(EXPIRY_WAIT_MS)
        before = sum(1 for m, u in requests if m == "POST" and "/api/auth/refresh" in u)
        outcome = page.evaluate("""Promise.all([apiCall('/api/employee/incidents'), apiCall('/api/notifications')])
                                       .then(() => 'ok').catch(e => 'failed: ' + e.message)""")
        after = sum(1 for m, u in requests if m == "POST" and "/api/auth/refresh" in u)
        check(outcome == "ok", "B. two simultaneous calls with an expired token both succeed (%s)" % outcome)
        check(after - before == 1, "B. exactly one refresh request was sent for the two calls (sent %d)" % (after - before))
        check(page.locator("#btn-logout").is_visible(), "B. the user is still signed in")

        current = stored_session(page)["refreshToken"]
        logouts = sum(1 for m, u in requests if m == "POST" and "/api/auth/logout" in u)
        page.click("#btn-logout")
        page.wait_for_selector("#form-login", timeout=15000)
        page.wait_for_timeout(500)
        sent = sum(1 for m, u in requests if m == "POST" and "/api/auth/logout" in u) - logouts
        check(sent == 1, "C. signing out sent one POST /api/auth/logout (sent %d)" % sent)
        check(post(args.base, "/api/auth/refresh", {"refreshToken": current}) == 401,
              "C. the refresh token that was in use is dead after sign-out")
        check(stored_session(page) is None, "C. nothing is left in sessionStorage")

        unexpected = [e for e in console_errors if not any(code in e for code in ("401", "404"))]
        check(not unexpected, "D. no console error other than the 401 answers and the favicon 404 (%s)" % unexpected)
        browser.close()

    print("\n%d failure(s)" % len(FAILURES))
    sys.exit(1 if FAILURES else 0)


if __name__ == "__main__":
    main()
