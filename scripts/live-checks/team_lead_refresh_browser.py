#!/usr/bin/env python3
"""Real-browser check for the Team Lead tab's refresh behaviour (ADR-045, Phase M2), run against a running application.

Usage (the application must be running and seeded; Python with Playwright and Microsoft Edge installed):

    python scripts/live-checks/team_lead_refresh_browser.py [--base http://localhost:8085] [--headed]

What it does, as the Network team lead (real login form):
  1. The roster shows only the four Network team members and "Updated hh:mm:ss" is shown (Phase M1 + M2).
  2. A new ticket is reported through the API; without any click the roster load total rises by one within the timer
     period, and the "Updated" time changes.
  3. During those automatic reloads the panel never falls back to "Loading..." text (no blanking, no flicker).
  4. While another tab is open the timer does not reload the Team Lead tab; coming back reloads it at once.
  5. A failing roster call during a reload keeps the old roster, says "Update failed", and leaves the queue lists
     alone; once the call works again the stamp says "Updated".
  6. After signing out and in again with the roster call failing from the start, the roster card shows its own error
     while the two ticket lists still load (one failure hides nothing).
  7. Throughout: no browser console error and no failed API call other than the injected roster failures.

The ticket it creates is resolved at the end. Exit code 0 when every check passes, 1 otherwise.
"""
import argparse
import json
import random
import re
import string
import sys
import time
import urllib.error
import urllib.request

from playwright.sync_api import sync_playwright

PASSWORD = "Password123!"
EMPLOYEE = "employee@nimbusfinserve.com"
LEAD = "lead.network@nimbusfinserve.com"
ADMIN = "admin@nimbusfinserve.com"
ROSTER_URL = "**/api/admin/technicians"
REFRESH_WAIT_MS = 30000

LOADS_JS = """() => [...document.querySelectorAll('#teamlead-roster-grid > .card')].map(c => {
    const m = c.textContent.match(/Active Tickets:\\s*(\\d+)/); return m ? Number(m[1]) : null; })"""
OBSERVER_JS = """() => {
    window.__sawLoading = false;
    const watch = (id) => new MutationObserver(() => {
        const text = document.getElementById(id).textContent;
        if (text.includes('Loading team roster') || text.includes('Checking unassigned') || text.includes('Checking team escalations')) {
            window.__sawLoading = true;
        }
    }).observe(document.getElementById(id), { childList: true, subtree: true, characterData: true });
    ['teamlead-roster-grid', 'teamlead-unassigned-container', 'teamlead-escalation-container'].forEach(watch);
}"""


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
        safe = text.encode("ascii", "replace").decode("ascii")  # page text may hold symbols a Windows console cannot print
        print("%-4s %s" % ("ok" if ok else "FAIL", safe))
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


def stamp(page):
    return page.locator("#teamlead-updated").inner_text().strip()


def wait_for_updated(page, timeout=15000):
    page.wait_for_function(
        "() => document.getElementById('teamlead-updated').textContent.startsWith('Updated')", timeout=timeout)


def resolve_ticket(base, ticket_id, admin):
    status, roster = call(base, "GET", "/api/admin/technicians", admin)
    status, detail = call(base, "GET", "/api/admin/incidents/" + ticket_id, admin)
    holder = next((t for t in roster if t["id"] == detail.get("assigneeId")), None)
    if holder is None:
        return
    token = login(base, holder["email"])
    call(base, "POST", "/api/technician/incidents/%s/acknowledge" % ticket_id, token)
    call(base, "POST", "/api/technician/incidents/%s/start-investigation" % ticket_id, token)
    call(base, "POST", "/api/technician/incidents/%s/resolve" % ticket_id, token, {
        "rootCause": "check ticket", "stepsTaken": "closed by the live check",
        "preventiveAction": "none", "confirmedCategory": "NETWORK"})


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default="http://localhost:8085")
    parser.add_argument("--headed", action="store_true", help="show the browser window")
    args = parser.parse_args()
    base = args.base
    rng = random.Random()
    checks = Checks()
    employee, admin = login(base, EMPLOYEE), login(base, ADMIN)
    created = []
    errors = []
    injected = {"count": 0}

    try:
        run_browser(args, base, rng, checks, employee, admin, created, errors, injected)
    finally:
        for ticket_id in created:
            resolve_ticket(base, ticket_id, admin)
    if checks.failures:
        print("\n%d check(s) FAILED" % len(checks.failures))
        sys.exit(1)
    print("\nall checks passed")


def run_browser(args, base, rng, checks, employee, admin, created, errors, injected):
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(channel="msedge", headless=not args.headed)
        page = browser.new_page()
        page.on("console", lambda m: errors.append("console error: %s" % m.text)
                if m.type == "error" and not m.text.startswith("Failed to load resource") else None)
        page.on("pageerror", lambda e: errors.append("page error: %s" % e))
        page.on("response", lambda r: errors.append("HTTP %s %s" % (r.status, r.url))
                if r.status >= 400 and not r.url.endswith("/favicon.ico") else None)

        # --- 1. the roster ---------------------------------------------------------------------------------------------
        sign_in(page, base, LEAD)
        page.wait_for_selector("#teamlead-roster-grid > .card", timeout=15000)
        wait_for_updated(page)
        loads = page.evaluate(LOADS_JS)
        checks.check(len(loads) == 4, "roster: exactly the 4 Network team members are shown (%d)" % len(loads))
        checks.check(re.fullmatch(r"Updated \d\d:\d\d:\d\d", stamp(page)) is not None,
                     "roster: the update time is shown: '%s'" % stamp(page))

        # --- 2 and 3. an update arrives without a click, and nothing blanks ----------------------------------------------
        page.evaluate(OBSERVER_JS)
        stamp_before, total_before = stamp(page), sum(l or 0 for l in loads)
        status, ticket = call(base, "POST", "/api/employee/incidents", employee, {
            "title": "VPN gateway " + words(rng, 2),
            "description": "The vpn and the gateway " + words(rng, 14) + " vpn."})
        if status not in (200, 201):
            sys.exit("FAIL: cannot create the ticket (HTTP %s)" % status)
        created.append(ticket["id"])
        rose = True
        try:
            page.wait_for_function(
                "([sel, before]) => [...document.querySelectorAll(sel)].reduce((s, c) => { const m = c.textContent.match(/Active Tickets:\\s*(\\d+)/); return s + (m ? Number(m[1]) : 0); }, 0) > before",
                arg=["#teamlead-roster-grid > .card", total_before], timeout=REFRESH_WAIT_MS)
        except Exception:
            rose = False
        checks.check(rose, "auto refresh: the roster load total rose from %d with no click" % total_before)
        checks.check(stamp(page) != stamp_before, "auto refresh: the update time moved ('%s' -> '%s')" % (stamp_before, stamp(page)))
        checks.check(not page.evaluate("() => window.__sawLoading"), "auto refresh: the panel never fell back to 'Loading...' text")

        # --- 4. the timer stays quiet on another tab --------------------------------------------------------------------
        page.click("button.nav-tab[data-target=console-technician]")
        quiet_stamp = stamp(page)
        time.sleep(13)
        checks.check(stamp(page) == quiet_stamp, "other tab: no reload of the Team Lead tab during 13 s")
        page.click("button.nav-tab[data-target=console-teamlead]")
        try:
            page.wait_for_function("(old) => document.getElementById('teamlead-updated').textContent !== old",
                                   arg=quiet_stamp, timeout=8000)
            moved = True
        except Exception:
            moved = False
        checks.check(moved, "other tab: coming back reloads the tab at once")
        wait_for_updated(page)

        # --- 5. a failing roster call keeps the old data -----------------------------------------------------------------
        def fail_roster(route):
            injected["count"] += 1
            route.fulfill(status=500, content_type="application/json",
                          body=json.dumps({"code": "ERR_INTERNAL_SERVER_ERROR", "message": "injected failure"}))

        page.route(ROSTER_URL, fail_roster)
        cards_before = len(page.evaluate(LOADS_JS))
        page.click("#btn-refresh-teamlead")
        page.wait_for_function(
            "() => document.getElementById('teamlead-updated').textContent.startsWith('Update failed')", timeout=10000)
        checks.check(len(page.evaluate(LOADS_JS)) == cards_before and cards_before == 4,
                     "failure: the 4 roster cards stay on screen after a failed reload")
        checks.check("showing the last data received" in stamp(page), "failure: the stamp says so: '%s'" % stamp(page))
        checks.check(page.locator("#teamlead-unassigned-container").inner_text().strip() != "",
                     "failure: the unassigned list is still drawn")
        page.unroute(ROSTER_URL)
        page.click("#btn-refresh-teamlead")
        wait_for_updated(page)
        checks.check(True, "failure: a working call brings back 'Updated'")

        # --- 6. one failure hides nothing on a fresh sign-in --------------------------------------------------------------
        sign_out(page)
        page.route(ROSTER_URL, fail_roster)
        sign_in(page, base, LEAD)
        page.wait_for_function(
            "() => document.getElementById('teamlead-roster-grid').textContent.includes('Could not load')", timeout=15000)
        page.wait_for_function(
            "() => !document.getElementById('teamlead-unassigned-container').textContent.includes('Checking unassigned')",
            timeout=15000)
        unassigned_text = page.locator("#teamlead-unassigned-container").inner_text()
        checks.check("Could not load" not in unassigned_text,
                     "first load: the roster error does not hide the ticket lists ('%s')" % unassigned_text.strip()[:60])
        page.unroute(ROSTER_URL)
        page.click("#btn-refresh-teamlead")
        page.wait_for_selector("#teamlead-roster-grid > .card", timeout=15000)
        checks.check(len(page.evaluate(LOADS_JS)) == 4, "first load: the roster appears once the call works")

        # --- 7. quiet console -----------------------------------------------------------------------------------------------
        unexpected = [e for e in errors if "/api/admin/technicians" not in e]
        checks.check(not unexpected, "browser: no console error and no failed request %s" % unexpected)
        checks.check(injected["count"] >= 2, "the roster failure was injected %d times" % injected["count"])
        browser.close()


if __name__ == "__main__":
    main()
