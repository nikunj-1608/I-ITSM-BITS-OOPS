#!/usr/bin/env python3
"""Real-browser check for the rebuilt Technician tab (ADR-051), against a running application.

Usage (application running and seeded; Python with Playwright and Microsoft Edge installed):

    mvn -o -Dmaven.test.skip=true spring-boot:run
    python scripts/live-checks/technician_portal_browser.py [--base http://localhost:8085] [--headed]

What it does
  1. Through the API: an employee reports two tickets with words no other ticket has; both are given to one technician.
  2. In the browser, as that technician (real login form): the capacity banner and the four counters are drawn; the
     queue is a table with the seven columns; both tickets are rows showing priority, status, requester and deadline.
  3. A row opens and closes with its Details button and with a click on the row; only one row is open at a time; an
     open row stays open when the queue is refreshed; the action bar is inside the opened row.
  4. Acknowledge and Start Investigation work from the row (the server confirms the status); the row stays open.
  5. The spotlight appears for the ticket in progress that the documented rule picks (breached first, earliest
     deadline, higher priority; compared with the API), is hidden in team mode and survives a status filter.
  6. The mode toggle follows aria-pressed; the status filter narrows the table; signing out clears an open row and
     the spotlight.
  7. Throughout: no console error and no failed request that was not expected.

The tickets it opens stay open (the technician's queue keeps them); each run uses fresh random words, so a repeated
run does not link them as duplicates. Exit code 0 when every check passes, 1 otherwise.
"""
import argparse
import json
import random
import string
import sys
import time
import urllib.error
import urllib.request

from playwright.sync_api import sync_playwright

PASSWORD = "Password123!"
EMPLOYEE = "employee@nimbusfinserve.com"
ADMIN = "admin@nimbusfinserve.com"
TECHNICIAN = "tech.net2@nimbusfinserve.com"
HEADERS = ["Ticket ID", "Priority", "Status", "Requester", "Date Raised", "Deadline", "Details"]
PRIORITY_ORDER = ["P1", "P2", "P3", "P4"]


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


def group(page, ticket_id):
    return page.locator("#tech-queue-container [data-ticket-card='%s']" % ticket_id)


def is_open(page, ticket_id):
    return group(page, ticket_id).locator(".tp-details-btn").get_attribute("aria-expanded") == "true"


def wait_status(base, token, ticket_id, status, timeout=15):
    deadline = time.time() + timeout
    while time.time() < deadline:
        code, body = call(base, "GET", "/api/technician/incidents/" + ticket_id, token)
        if code == 200 and body["status"] == status:
            return True
        time.sleep(0.4)
    return False


def expected_spotlight(queue, my_id):
    mine = [t for t in queue if t["status"] == "IN_PROGRESS" and t.get("assigneeId") == my_id]
    if not mine:
        return None

    def key(t):
        due = t.get("resolutionDueAt") or t.get("responseDueAt")
        due_ms = float("inf")
        if due:
            from datetime import datetime
            due_ms = datetime.fromisoformat(due.replace("Z", "+00:00")).timestamp()
        priority = PRIORITY_ORDER.index(t["priority"]) if t.get("priority") in PRIORITY_ORDER else 9
        return (0 if t.get("slaStatus") == "BREACHED" else 1, due_ms, priority)

    return sorted(mine, key=key)[0]["id"]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default="http://localhost:8085")
    parser.add_argument("--headed", action="store_true")
    args = parser.parse_args()
    base = args.base
    rng = random.Random()
    checks = Checks()

    employee, admin, tech_token = login(base, EMPLOYEE), login(base, ADMIN), login(base, TECHNICIAN)
    status, roster = call(base, "GET", "/api/admin/technicians", admin)
    me = next(t for t in roster if t["email"] == TECHNICIAN)["id"]
    ids = []
    for label in ("alpha", "bravo"):
        status, ticket = call(base, "POST", "/api/employee/incidents", employee, {
            "title": "VPN gateway " + words(rng, 2),
            "description": "The vpn and the gateway " + words(rng, 14) + " vpn %s." % label})
        if status not in (200, 201):
            sys.exit("FAIL: cannot report a ticket (HTTP %s %s)" % (status, ticket))
        status, _ = call(base, "POST", "/api/admin/incidents/%s/assign" % ticket["id"], admin, {"technicianId": me})
        if status != 200:
            sys.exit("FAIL: cannot assign %s to %s (HTTP %s)" % (ticket["id"], me, status))
        ids.append(ticket["id"])
    a, b = ids
    checks.check(True, "setup: tickets %s and %s are assigned to %s" % (a, b, me))

    errors = []
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(channel="msedge", headless=not args.headed)
        page = browser.new_page()
        page.on("console", lambda m: errors.append("console error: %s" % m.text)
                if m.type == "error" and not m.text.startswith("Failed to load resource") else None)
        page.on("pageerror", lambda e: errors.append("page error: %s" % e))
        page.on("response", lambda r: errors.append("HTTP %s %s" % (r.status, r.url))
                if r.status >= 400 and not r.url.endswith("/favicon.ico") else None)

        # --- 1. the page ---------------------------------------------------------------------------------------------
        sign_in(page, base, TECHNICIAN)
        page.click("button.nav-tab[data-target=console-technician]")
        group(page, a).wait_for(timeout=15000)
        group(page, b).wait_for(timeout=15000)
        page.wait_for_timeout(800)
        text = page.inner_text("#tech-capacity-text")
        checks.check("Active Tickets" in text and "%" in text, "banner: the capacity text is drawn (%s)" % text)
        badge = page.get_attribute("#tech-capacity-status", "class") or ""
        checks.check("tp-load-badge" in badge and "tp-load-badge--" in badge, "banner: the load badge has a band (%s)" % badge)
        checks.check(page.get_attribute("#tp-capacity-bar", "aria-valuenow") not in (None, ""), "banner: the bar reports its value")
        counters = [page.inner_text("#tech-stat-" + k).strip() for k in ("assigned", "investigating", "pending", "atrisk")]
        checks.check(all(c.isdigit() for c in counters), "counters: four numbers are drawn %s" % counters)
        headers = page.locator("#tech-queue-container .tp-table th").all_inner_texts()
        checks.check([h.strip() for h in headers] == HEADERS, "table: the seven columns %s" % headers)
        row_a = group(page, a).locator(".tp-row")
        checks.check(group(page, a).locator(".tp-pri").count() == 1 and group(page, a).locator(".tp-status").count() == 1,
                     "table: the row shows a priority and a status chip")
        checks.check(row_a.locator(".tp-requester-name").inner_text().strip() != "", "table: the row shows the requester")
        checks.check(row_a.locator(".tp-deadline-date").inner_text().strip() not in ("", "--"), "table: the row shows a deadline")

        # --- 2. open and close a row -------------------------------------------------------------------------------
        checks.check(not is_open(page, a) and not group(page, a).locator(".tp-detail-row").is_visible(),
                     "rows: a row is closed to begin with")
        group(page, a).locator(".tp-details-btn").click()
        checks.check(is_open(page, a) and group(page, a).locator(".tp-detail-row").is_visible(), "rows: Details opens the row")
        checks.check(group(page, a).locator(".btn-tech-ack").is_visible(), "rows: the action bar (Acknowledge) is inside the open row")
        group(page, a).locator(".tp-details-btn").click()
        checks.check(not is_open(page, a), "rows: Details closes it again")
        row_a.click()
        checks.check(is_open(page, a), "rows: a click on the row opens it")
        group(page, b).locator(".tp-details-btn").click()
        checks.check(is_open(page, b) and not is_open(page, a), "rows: opening B closes A (one open row)")
        page.click("#btn-refresh-tech-queue")
        page.wait_for_timeout(1200)
        checks.check(is_open(page, b), "rows: the open row stays open after a refresh")

        # --- 3. actions from the row -----------------------------------------------------------------------------------
        group(page, a).locator(".tp-details-btn").click()
        group(page, a).locator(".btn-tech-ack").click()
        checks.check(wait_status(base, tech_token, a, "ACKNOWLEDGED"), "actions: Acknowledge worked (the server shows ACKNOWLEDGED)")
        page.wait_for_timeout(1200)
        checks.check(is_open(page, a), "actions: the row stays open after the action")
        group(page, a).locator(".btn-tech-investigate").click()
        checks.check(wait_status(base, tech_token, a, "IN_PROGRESS"), "actions: Start Investigation worked (IN_PROGRESS)")
        page.wait_for_timeout(1500)

        # --- 4. the spotlight --------------------------------------------------------------------------------------------
        status, queue = call(base, "GET", "/api/technician/queue", tech_token)
        want = expected_spotlight(queue, me)
        spot = page.locator("#tp-spotlight")
        checks.check(want is not None and spot.is_visible(), "spotlight: shown while a ticket of mine is in progress")
        checks.check(want is not None and want in spot.inner_text(),
                     "spotlight: it shows the ticket the rule picks (%s)" % want)
        page.select_option("#tech-queue-filter", "ASSIGNED")
        page.wait_for_timeout(1200)
        checks.check(spot.is_visible(), "spotlight: a status filter does not hide it")
        rows = page.locator("#tech-queue-container .tp-ticket").count()
        statuses = page.locator("#tech-queue-container .tp-ticket .tp-status").all_inner_texts()
        checks.check(all(s.strip() in ("Assigned", "At-Risk SLA", "Breached SLA") for s in statuses),
                     "filter: only assigned tickets are listed (%d rows)" % rows)
        page.select_option("#tech-queue-filter", "ALL")
        page.wait_for_timeout(1200)
        spot.locator(".btn-tp-spot-open").click()
        page.wait_for_timeout(400)
        checks.check(is_open(page, want), "spotlight: View Details opens that ticket's row")
        checks.check(spot.locator(".btn-tp-spot-resolve").count() == 1 and spot.locator(".btn-tp-spot-reqinfo").count() == 1,
                     "spotlight: Request Extra Info and Mark Resolved are offered")

        # --- 5. the mode toggle -------------------------------------------------------------------------------------------
        pressed = lambda s: page.get_attribute("#btn-tech-mode-" + s, "aria-pressed")
        checks.check(pressed("my") == "true" and pressed("team") == "false", "toggle: 'Assigned to Me' is pressed to begin with")
        page.click("#btn-tech-mode-team")
        page.wait_for_timeout(1500)
        checks.check(pressed("team") == "true" and pressed("my") == "false", "toggle: team mode is pressed")
        checks.check(not spot.is_visible(), "spotlight: hidden in team mode")
        page.click("#btn-tech-mode-my")
        page.wait_for_timeout(1500)
        checks.check(pressed("my") == "true" and spot.is_visible(), "toggle: back to my queue, the spotlight returns")

        # --- 6. sign-out clears the tab ------------------------------------------------------------------------------------
        sign_out(page)
        sign_in(page, base, TECHNICIAN)
        page.click("button.nav-tab[data-target=console-technician]")
        group(page, a).wait_for(timeout=15000)
        checks.check(page.locator("#tech-queue-container .tp-ticket.is-open").count() == 0,
                     "sign-out: no row is open after signing in again")
        sign_out(page)
        browser.close()

    checks.check(not errors, "browser: no console error and no unexpected failed request %s" % errors)
    sys.exit(1 if checks.failures else 0)


if __name__ == "__main__":
    main()
