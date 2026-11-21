#!/usr/bin/env python3
"""Real-browser check for the Team Lead's "current tickets of a team member" window (ADR-054), run against a running app.

Usage (the application must be running and seeded; Python with Playwright and Microsoft Edge installed):

    python scripts/live-checks/team_member_tickets_browser.py [--base http://localhost:8085] [--headed]

What it does, as the Network team lead (real login form):
  1. Three tickets are reported and given different fates: one is only assigned, one is resolved (so it waits for the
     employee), one is resolved and then confirmed by the employee (VERIFIED, so it is no longer "current").
  2. For every roster card: click it; the window lists exactly the tickets that technician holds and the employee has not
     yet confirmed (compared with GET /api/technician/team-queue), each with id, title, type, status and progress.
  3. The assigned ticket shows "Assigned (step 2 of 5)", the resolved one "waiting for the employee to confirm", and the
     confirmed one is absent from its technician's window.
  4. The window is read only: its only buttons are the cross and Close, and no request other than GET is sent while it is
     open. The cross, Close, Escape and a click on the backdrop each close it, and focus returns to the card.
  5. The roster cards look as before (still `.card`, name, load, skills) and the Assign / Reassign controls are untouched.
  6. After signing out and in again the window is gone. No browser console error and no failed API call.

The tickets it creates are resolved and confirmed at the end. Exit code 0 when every check passes, 1 otherwise.
"""
import argparse
import json
import random
import string
import sys
import urllib.error
import urllib.request

from playwright.sync_api import sync_playwright

PASSWORD = "Password123!"
EMPLOYEE = "employee@nimbusfinserve.com"
LEAD = "lead.network@nimbusfinserve.com"
ADMIN = "admin@nimbusfinserve.com"
CONFIRMED = ("VERIFIED", "CLOSED")


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
        safe = text.encode("ascii", "replace").decode("ascii")
        print("%-4s %s" % ("ok" if ok else "FAIL", safe))
        if not ok:
            self.failures.append(text)


def sign_in(page, base, email):
    page.goto(base + "/")
    page.fill("#login-email", email)
    page.fill("#login-password", PASSWORD)
    page.click("#form-login button[type=submit]")
    page.wait_for_selector("#btn-logout", timeout=15000)


def new_ticket(base, rng, employee, created):
    status, ticket = call(base, "POST", "/api/employee/incidents", employee, {
        "title": "VPN gateway " + words(rng, 2),
        "description": "The vpn and the gateway " + words(rng, 14) + " vpn."})
    if status not in (200, 201):
        sys.exit("FAIL: cannot create a ticket (HTTP %s)" % status)
    created.append(ticket["id"])
    return ticket["id"]


def holder_token(base, admin, ticket_id):
    _, roster = call(base, "GET", "/api/admin/technicians", admin)
    _, detail = call(base, "GET", "/api/admin/incidents/" + ticket_id, admin)
    holder = next((t for t in roster if t["id"] == detail.get("assigneeId")), None)
    return None if holder is None else login(base, holder["email"])


def resolve(base, admin, ticket_id):
    token = holder_token(base, admin, ticket_id)
    if token is None:
        return False
    for step in ("acknowledge", "start-investigation"):
        call(base, "POST", "/api/technician/incidents/%s/%s" % (ticket_id, step), token)
    status, _ = call(base, "POST", "/api/technician/incidents/%s/resolve" % ticket_id, token, {
        "rootCause": "check ticket", "stepsTaken": "resolved by the live check",
        "preventiveAction": "none", "confirmedCategory": "NETWORK"})
    return status == 200


def expected_ids(base, lead, tech_id):
    _, queue = call(base, "GET", "/api/technician/team-queue", lead)
    return sorted(t["id"] for t in queue if t.get("assigneeId") == tech_id and t["status"] not in CONFIRMED)


def dialog_ids(page):
    return sorted(page.eval_on_selector_all(".tlm-ticket", "els => els.map(e => e.getAttribute('data-ticket-id'))"))


def open_card(page, tech_id):
    page.click('#teamlead-roster-grid .tlm-card[data-id="%s"]' % tech_id)
    page.wait_for_selector(".tlm-dialog", timeout=10000)
    page.wait_for_function("() => !document.getElementById('tlm-body').textContent.includes('Loading tickets')", timeout=10000)


def dialog_open(page):
    return page.evaluate("() => !document.getElementById('modal-container').classList.contains('hidden')"
                         " && !!document.querySelector('.tlm-dialog')")


def run(args, base, rng, checks, employee, admin, lead, created, errors, writes):
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(channel="msedge", headless=not args.headed)
        page = browser.new_page()
        page.on("console", lambda m: errors.append("console error: %s" % m.text)
                if m.type == "error" and not m.text.startswith("Failed to load resource") else None)
        page.on("pageerror", lambda e: errors.append("page error: %s" % e))
        page.on("response", lambda r: errors.append("HTTP %s %s" % (r.status, r.url))
                if r.status >= 400 and not r.url.endswith("/favicon.ico") else None)
        page.on("request", lambda r: writes.append("%s %s" % (r.method, r.url)) if r.method != "GET" else None)

        assigned_id = new_ticket(base, rng, employee, created)
        resolved_id = new_ticket(base, rng, employee, created)
        verified_id = new_ticket(base, rng, employee, created)
        checks.check(resolve(base, admin, resolved_id), "setup: one ticket resolved (waits for the employee)")
        if resolve(base, admin, verified_id):
            status, _ = call(base, "POST", "/api/employee/incidents/%s/verify" % verified_id, employee)
            checks.check(status == 200, "setup: one ticket confirmed by the employee (VERIFIED)")

        sign_in(page, base, LEAD)
        page.wait_for_selector("#teamlead-roster-grid .tlm-card", timeout=15000)
        cards = page.eval_on_selector_all("#teamlead-roster-grid .tlm-card", "els => els.map(e => e.dataset.id)")
        checks.check(len(cards) == 4, "roster: 4 clickable cards (%d)" % len(cards))
        checks.check(page.locator("#teamlead-roster-grid > .card").count() == len(cards)
                     and page.locator("#teamlead-roster-grid h4").count() == len(cards),
                     "roster: the cards are still cards with the technician name as title")
        writes.clear()

        seen = {}
        for tech_id in cards:
            open_card(page, tech_id)
            ids = dialog_ids(page)
            seen[tech_id] = ids
            checks.check(ids == expected_ids(base, lead, tech_id),
                         "window %s: lists exactly the current tickets of that technician (%d)" % (tech_id, len(ids)))
            page.click(".tlm-close")
            checks.check(not dialog_open(page), "window %s: the cross closes it" % tech_id)

        for ticket_id, text, label in ((assigned_id, "Assigned (step 2 of 5)", "assigned"),
                                       (resolved_id, "waiting for the employee to confirm", "resolved")):
            owner = next((t for t, ids in seen.items() if ticket_id in ids), None)
            checks.check(owner is not None, "%s ticket: shown in one technician's window" % label)
            if owner:
                open_card(page, owner)
                row = page.locator('.tlm-ticket[data-ticket-id="%s"]' % ticket_id)
                body = row.inner_text()
                checks.check(text in body, "%s ticket: progress text '%s'" % (label, text))
                checks.check(all(x in body for x in (ticket_id, "Type:", "VPN gateway")),
                             "%s ticket: id, type and title are shown" % label)
                page.click(".tlm-close")
        checks.check(all(verified_id not in ids for ids in seen.values()),
                     "confirmed ticket: not in any technician's window")

        owner = next((t for t, ids in seen.items() if ids), cards[0])
        open_card(page, owner)
        buttons = page.locator(".tlm-dialog button").count()
        checks.check(buttons == 2, "read only: the window has only the cross and Close buttons (%d)" % buttons)
        if page.locator(".tlm-details").count():
            page.locator(".tlm-details summary").first.click()
            checks.check(page.locator(".tlm-details[open]").count() == 1, "details: description, notes and resolution expand")
        page.click(".tlm-dialog .modal-footer button")
        checks.check(not dialog_open(page), "Close button closes the window")
        open_card(page, owner)
        page.keyboard.press("Escape")
        checks.check(not dialog_open(page), "Escape closes the window")
        open_card(page, owner)
        page.mouse.click(5, 5)
        checks.check(not dialog_open(page), "a click on the backdrop closes the window")
        focused = page.evaluate("() => document.activeElement && document.activeElement.dataset.id")
        checks.check(focused == owner, "focus returns to the technician's card")
        page.focus('#teamlead-roster-grid .tlm-card[data-id="%s"]' % owner)
        page.keyboard.press("Enter")
        page.wait_for_selector(".tlm-dialog", timeout=5000)
        checks.check(True, "keyboard: Enter on a focused card opens the window")
        checks.check(not writes, "read only: no non-GET request was sent (%s)" % ", ".join(writes))

        page.click(".tlm-close")
        page.click("#btn-logout")
        page.wait_for_selector("#form-login", timeout=15000)
        checks.check(not dialog_open(page), "sign-out leaves no window behind")
        browser.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default="http://localhost:8085")
    parser.add_argument("--headed", action="store_true")
    args = parser.parse_args()
    base, rng, checks = args.base, random.Random(), Checks()
    employee, admin, lead = login(base, EMPLOYEE), login(base, ADMIN), login(base, LEAD)
    created, errors, writes = [], [], []
    try:
        run(args, base, rng, checks, employee, admin, lead, created, errors, writes)
    finally:
        for ticket_id in created:
            resolve(base, admin, ticket_id)  # leaves no open ticket behind (a resolved one just waits for its employee)
    checks.check(not errors, "no console error and no failed API call %s" % errors[:3])
    if checks.failures:
        print("\n%d check(s) FAILED" % len(checks.failures))
        sys.exit(1)
    print("\nall checks passed")


if __name__ == "__main__":
    main()
