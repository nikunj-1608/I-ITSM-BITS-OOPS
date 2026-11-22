#!/usr/bin/env python3
"""Real-browser check for ADR-032 (team view) and ADR-033 (registration message), against a running application.

Usage (application running and seeded; Python with Playwright and Microsoft Edge installed). Start it with registration
limited to the company domain to see the message:

    mvn -o -Dmaven.test.skip=true spring-boot:run "-Dspring-boot.run.arguments=--iitsm.security.allowed-email-domains=nimbusfinserve.com"
    python scripts/live-checks/team_view_browser.py [--base http://localhost:8085] [--headed]

What it does
  1. Through the API: an employee reports a VPN ticket and it is assigned to a technician of the Network team.
  2. In the browser, as a colleague of the assignee (real login form): the Technician tab's team button reads
     "Unassigned Team Tickets"; clicking it does not list the ticket; the assignee's own queue does.
  3. In the browser, as the team lead: the Team Lead tab loads the team queue (it lists unassigned and at-risk tickets
     only, so a healthy assigned ticket does not show there; the API check in security_checks.py covers the lead's view).
  4. In the browser, on the registration form: an external e-mail address shows the server's message and no account exists.
  5. Throughout: no browser console error and no failed API call that was not expected.

Exit code 0 when every check passes, 1 otherwise.
"""
import argparse
import json
import random
import string
import sys
import uuid
import urllib.error
import urllib.request

from playwright.sync_api import sync_playwright

PASSWORD = "Password123!"
EMPLOYEE = "employee@nimbusfinserve.com"
ADMIN = "admin@nimbusfinserve.com"


def call(base, method, path, token=None, body=None):
    data = None if body is None else json.dumps(body).encode("utf-8")
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


def login(base, email):
    status, body = call(base, "POST", "/api/auth/login", body={"email": email, "password": PASSWORD})
    if status != 200 or "token" not in (body or {}):
        sys.exit("FAIL: cannot log in as %s (HTTP %s)" % (email, status))
    return body["token"]


def words(rng, n):
    return " ".join("".join(rng.choice(string.ascii_lowercase) for _ in range(9)) for _ in range(n))


class Checks:
    def __init__(self):
        self.failures = []

    def check(self, ok, text):
        print("%-4s %s" % ("ok" if ok else "FAIL", text))
        if not ok:
            self.failures.append(text)


def sign_in(page, base, email):
    page.goto(base + "/")
    page.fill("#login-email", email)
    page.fill("#login-password", PASSWORD)
    page.click("#form-login button[type=submit]")
    page.wait_for_selector("#btn-logout", timeout=15000)


def sign_out(page):
    page.click("#btn-logout")
    page.wait_for_selector("#form-login", timeout=15000)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default="http://localhost:8085")
    parser.add_argument("--headed", action="store_true")
    args = parser.parse_args()
    base = args.base
    rng = random.Random()
    checks = Checks()

    employee, admin = login(base, EMPLOYEE), login(base, ADMIN)
    status, ticket = call(base, "POST", "/api/employee/incidents", employee, {
        "title": "VPN gateway " + words(rng, 2), "description": "The vpn and the gateway " + words(rng, 14) + " vpn."})
    ticket_id = ticket["id"]
    status, detail = call(base, "GET", "/api/admin/incidents/" + ticket_id, admin)
    assignee_id = detail.get("assigneeId")
    if not assignee_id:
        sys.exit("FAIL: the ticket was not auto-assigned")
    status, roster = call(base, "GET", "/api/admin/technicians", admin)
    by_id = {t["id"]: t for t in roster}
    team = by_id[assignee_id]["supportTeamId"]
    colleague = next((t["email"] for t in roster if t["supportTeamId"] == team and t["id"] != assignee_id
                      and t["role"] == "ROLE_TECHNICIAN"), None)
    lead = next((t["email"] for t in roster if t["supportTeamId"] == team and t["role"] == "ROLE_TEAM_LEAD"), None)
    if colleague is None or lead is None:
        sys.exit("FAIL: the team of %s has no colleague or lead to test with" % assignee_id)
    checks.check(True, "setup: ticket %s is assigned to %s; colleague %s, lead %s" % (ticket_id, assignee_id, colleague, lead))

    errors = []
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(channel="msedge", headless=not args.headed)
        page = browser.new_page()
        page.on("console", lambda m: errors.append("console error: %s" % m.text)
                if m.type == "error" and not m.text.startswith("Failed to load resource") else None)
        page.on("pageerror", lambda e: errors.append("page error: %s" % e))
        page.on("response", lambda r: errors.append("HTTP %s %s" % (r.status, r.url))
                if r.status >= 400 and not r.url.endswith("/favicon.ico") else None)

        # --- 2. a colleague of the assignee
        sign_in(page, base, colleague)
        page.click("button.nav-tab[data-target=console-technician]")
        button = page.locator("#btn-tech-mode-team")
        checks.check("Unassigned Team Tickets" in button.inner_text(), "colleague: the team button reads 'Unassigned Team Tickets'")
        button.click()
        page.wait_for_function("() => document.querySelector('#btn-tech-mode-team').getAttribute('aria-pressed') === 'true'",
                               timeout=15000)
        page.wait_for_timeout(1500)
        checks.check(ticket_id not in page.locator("#tech-queue-container").inner_text(),
                     "colleague: the team view does not list the assignee's ticket")
        sign_out(page)

        # --- the assignee's own queue
        sign_in(page, base, by_id[assignee_id]["email"])
        page.click("button.nav-tab[data-target=console-technician]")
        page.locator("#tech-queue-container [data-ticket-card]", has_text=ticket_id).first.wait_for(timeout=15000)
        checks.check(True, "assignee: the ticket is in their own queue")
        sign_out(page)

        # --- 3. the team lead
        queue_answers = []
        page.on("response", lambda r: queue_answers.append(r.status) if r.url.endswith("/api/technician/team-queue") else None)
        sign_in(page, base, lead)
        page.click("button.nav-tab[data-target=console-teamlead]")
        page.wait_for_timeout(2500)
        checks.check(page.locator("#console-teamlead").is_visible() and 200 in queue_answers,
                     "lead: the Team Lead tab renders and the team queue answered HTTP 200 (%s)" % queue_answers)
        sign_out(page)

        # --- 4. registration with an external address
        page.goto(base + "/")
        page.evaluate("""() => { document.getElementById('login-card').classList.add('hidden'); document.getElementById('register-card').classList.remove('hidden'); }""")  # the sign-in card has no Self-Register button (ADR-055)
        page.fill("#reg-name", "Outsider")
        page.fill("#reg-email", "outsider.%s@gmail.com" % uuid.uuid4().hex[:6])
        page.fill("#reg-password", PASSWORD)
        page.click("#btn-register-submit")
        page.wait_for_selector("#register-error-alert:not(.hidden)", timeout=15000)
        text = page.locator("#register-error-alert").inner_text()
        checks.check("e-mail domains" in text, "registration: the form shows the server's message (%s)" % text.replace("\n", " ")[:90])
        browser.close()

    expected = [e for e in errors if not e.startswith("HTTP 400 ") or "/api/auth/register" not in e]
    checks.check(not expected, "browser: no console error and no unexpected failed request %s" % expected)

    if checks.failures:
        print("FAILED: %d check(s)" % len(checks.failures))
        sys.exit(1)
    print("OK: the team view and the registration message work in a real browser")


if __name__ == "__main__":
    main()
