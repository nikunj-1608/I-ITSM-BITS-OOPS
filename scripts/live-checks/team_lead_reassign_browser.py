#!/usr/bin/env python3
"""Real-browser check for the team lead's reassignment and the M4 button fixes (ADR-045), against a running application.

Usage: start the application with a short SLA scan interval so a ticket becomes overdue while the script waits

    mvn -o -Dmaven.test.skip=true spring-boot:run -Dspring-boot.run.arguments=--iitsm.sla.scan-interval-ms=3000
    python scripts/live-checks/team_lead_reassign_browser.py [--base http://localhost:8085] [--headed]

(Python with Playwright and Microsoft Edge installed.) The simulation clock is moved to make the ticket overdue and is
reset at the end.

What it does, in the browser as the Network team lead (real login form), with one Network ticket held by a team member:
  1. The overdue ticket is in the SLA Escalation Monitor with "Reassign" and "Inspect Escalation".
  2. "Inspect Escalation" opens the Technician tab in team mode (the mode buttons follow), clears the status filter and
     highlights that ticket's card.
  3. The card offers "Reassign" and labels the acknowledge button "Acknowledge for <holder>".
  4. The dialog lists the team only, disables the current holder ("Current technician"), refuses to send without a
     technician or without a reason, and then moves the ticket: the server shows the new holder and ASSIGNED, the
     card shows the new assignee without any click.
  5. The monitor's own "Reassign" opens the same dialog.
  6. As the new holder (a plain technician) the same ticket has no "Reassign" button.
  7. Throughout: no console error and no failed request.

The ticket is resolved at the end. Exit code 0 when every check passes, 1 otherwise.
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
OVERDUE_MINUTES = 3000
SCAN_WAIT_SECONDS = 9


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


def sign_out(page):
    page.click("#btn-logout")
    page.wait_for_selector("#form-login", timeout=15000)


def toast_text(page):
    return " | ".join(page.locator("#toast-container .toast").all_inner_texts())


def clear_toasts(page):
    page.evaluate("() => document.querySelectorAll('#toast-container .toast').forEach(t => t.remove())")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default="http://localhost:8085")
    parser.add_argument("--headed", action="store_true", help="show the browser window")
    args = parser.parse_args()
    base = args.base
    rng = random.Random()
    checks = Checks()
    employee, lead, admin = login(base, EMPLOYEE), login(base, LEAD), login(base, ADMIN)
    state = {"ticket": None}
    try:
        run(args, base, rng, checks, employee, lead, admin, state)
    finally:
        call(base, "POST", "/api/simulation/reset", admin)
        resolve_ticket(base, state["ticket"], admin)
    if checks.failures:
        print("\n%d check(s) FAILED" % len(checks.failures))
        sys.exit(1)
    print("\nall checks passed")


def resolve_ticket(base, ticket_id, admin):
    if not ticket_id:
        return
    status, detail = call(base, "GET", "/api/admin/incidents/" + ticket_id, admin)
    status, roster = call(base, "GET", "/api/admin/technicians", admin)
    holder = next((t for t in roster if t["id"] == (detail or {}).get("assigneeId")), None)
    if holder is None:
        return
    token = login(base, holder["email"])
    call(base, "POST", "/api/technician/incidents/%s/acknowledge" % ticket_id, token)
    call(base, "POST", "/api/technician/incidents/%s/start-investigation" % ticket_id, token)
    call(base, "POST", "/api/technician/incidents/%s/resolve" % ticket_id, token, {
        "rootCause": "check ticket", "stepsTaken": "closed by the live check",
        "preventiveAction": "none", "confirmedCategory": "NETWORK"})


def run(args, base, rng, checks, employee, lead, admin, state):
    # --- setup through the API: a Network ticket held by a regular team member, made overdue -------------------------
    status, ticket = call(base, "POST", "/api/employee/incidents", employee, {
        "title": "VPN gateway " + words(rng, 2),
        "description": "The vpn and the gateway " + words(rng, 14) + " vpn."})
    if status not in (200, 201):
        sys.exit("FAIL: cannot create the ticket (HTTP %s)" % status)
    ticket_id = state["ticket"] = ticket["id"]
    status, roster = call(base, "GET", "/api/admin/technicians", lead)
    members = [t for t in roster if t["role"] == "ROLE_TECHNICIAN" and t["availableForAssignment"]]
    status, detail = call(base, "GET", "/api/admin/incidents/" + ticket_id, admin)
    holder_id = detail.get("assigneeId")
    holder = next((t for t in members if t["id"] == holder_id), None)
    if holder is None:
        holder = members[0]
        call(base, "POST", "/api/admin/incidents/%s/assign" % ticket_id, admin, {"technicianId": holder["id"]})
    target = next(t for t in members if t["id"] != holder["id"])
    others_outside_team = [t for t in call(base, "GET", "/api/admin/technicians", admin)[1] if t["supportTeamId"] != holder["supportTeamId"]]
    call(base, "POST", "/api/simulation/advance?minutes=%d" % OVERDUE_MINUTES, admin)
    time.sleep(SCAN_WAIT_SECONDS)

    errors = []
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(channel="msedge", headless=not args.headed)
        page = browser.new_page()
        page.on("console", lambda m: errors.append("console error: %s" % m.text)
                if m.type == "error" and not m.text.startswith("Failed to load resource") else None)
        page.on("pageerror", lambda e: errors.append("page error: %s" % e))
        page.on("response", lambda r: errors.append("HTTP %s %s" % (r.status, r.url))
                if r.status >= 400 and not r.url.endswith("/favicon.ico") else None)

        # --- 1. the escalation monitor -----------------------------------------------------------------------------------
        sign_in(page, base, LEAD)
        entry = page.locator("#teamlead-escalation-container .card", has_text=ticket_id)
        entry.wait_for(timeout=20000)
        checks.check(entry.locator(".btn-teamlead-reassign").count() == 1, "monitor: the overdue ticket offers Reassign")
        checks.check(entry.locator(".btn-teamlead-intervene").count() == 1, "monitor: the overdue ticket offers Inspect Escalation")

        # --- 2. Inspect Escalation ----------------------------------------------------------------------------------------
        # a status filter that would hide the ticket is set first; Inspect Escalation has to clear it
        page.evaluate("() => { document.getElementById('tech-queue-filter').value = 'RESOLVED'; }")
        entry.locator(".btn-teamlead-intervene").click()
        card = page.locator("#tech-queue-container [data-ticket-card='%s']" % ticket_id)
        card.wait_for(timeout=15000)
        checks.check(page.get_attribute("#btn-tech-mode-team", "aria-pressed") == "true",
                     "inspect: the team-mode button is shown as active")
        checks.check(page.get_attribute("#btn-tech-mode-my", "aria-pressed") == "false",
                     "inspect: the my-queue button is not shown as active")
        checks.check(page.evaluate("() => document.getElementById('tech-queue-filter').value") == "ALL",
                     "inspect: the status filter was cleared so the ticket cannot be hidden")
        highlighted = False
        for _ in range(10):
            if "ticket-focus" in (card.get_attribute("class") or ""):
                highlighted = True
                break
            page.wait_for_timeout(150)
        checks.check(highlighted, "inspect: the ticket's card is highlighted")

        # --- 3. the card ---------------------------------------------------------------------------------------------------
        card = page.locator("#tech-queue-container [data-ticket-card='%s']" % ticket_id)
        checks.check(card.locator(".btn-tech-reassign").count() == 1, "card: Reassign is offered")
        ack = card.locator(".btn-tech-ack")
        checks.check(ack.count() == 1 and ("Acknowledge for " + holder["name"]) in ack.inner_text(),
                     "card: the acknowledge button says it acts for %s ('%s')" % (holder["name"], ack.inner_text() if ack.count() else ""))

        # --- 4. the dialog and the move -------------------------------------------------------------------------------------
        card.locator(".btn-tech-reassign").click()
        page.wait_for_selector("#form-modal-team-reassign", timeout=10000)
        options = page.locator("#team-reassign-options .team-reassign-option")
        names = options.all_inner_texts()
        checks.check(len(names) == 4, "dialog: exactly the 4 Network team members are listed (%d)" % len(names))
        outsiders = [t["name"] for t in others_outside_team if any(t["name"] in n for n in names)]
        checks.check(not outsiders, "dialog: nobody from another team is listed %s" % outsiders)
        holder_option = page.locator("#team-reassign-options .team-reassign-option", has_text=holder["name"])
        marker = holder_option.inner_text()
        checks.check(holder_option.locator("input").is_disabled() and "current technician" in marker.lower(),
                     "dialog: the current holder is disabled and marked 'Current technician' (%r)" % marker.replace(chr(10), " "))
        sent = []
        page.on("request", lambda r: sent.append(r.url) if "/reassign" in r.url else None)
        clear_toasts(page)
        page.fill("#modal-team-reassign-reason", "Marcus knows this gateway better")
        page.click("#form-modal-team-reassign button[type=submit]")
        page.wait_for_timeout(500)
        checks.check("Choose a technician" in toast_text(page) and not sent, "dialog: nothing is sent without a technician")
        clear_toasts(page)
        page.locator("#team-reassign-options .team-reassign-option", has_text=target["name"]).locator("input").check()
        page.fill("#modal-team-reassign-reason", "   ")
        page.click("#form-modal-team-reassign button[type=submit]")
        page.wait_for_timeout(500)
        checks.check("reason is required" in toast_text(page) and not sent, "dialog: nothing is sent without a reason")
        clear_toasts(page)
        page.fill("#modal-team-reassign-reason", "Marcus knows this gateway better")
        page.click("#form-modal-team-reassign button[type=submit]")
        page.wait_for_selector("#form-modal-team-reassign", state="hidden", timeout=10000)
        status, after = call(base, "GET", "/api/admin/incidents/" + ticket_id, admin)
        checks.check(after["assigneeId"] == target["id"] and after["status"] == "ASSIGNED",
                     "move: the server shows %s and ASSIGNED (%s, %s)" % (target["id"], after["assigneeId"], after["status"]))
        checks.check("given to" in toast_text(page), "move: the lead sees a confirmation (%s)" % toast_text(page)[:80])
        refreshed = page.locator("#tech-queue-container [data-ticket-card='%s']" % ticket_id)
        try:
            refreshed.locator(".tp-muted", has_text=target["name"]).wait_for(timeout=8000)
            seen = True
        except Exception:
            seen = False
        checks.check(seen, "move: the card shows the new assignee %s without a click" % target["name"])

        # --- 5. the monitor's own Reassign ----------------------------------------------------------------------------------
        page.click("button.nav-tab[data-target=console-teamlead]")
        entry = page.locator("#teamlead-escalation-container .card", has_text=ticket_id)
        entry.wait_for(timeout=15000)
        entry.locator(".btn-teamlead-reassign").click()
        page.wait_for_selector("#form-modal-team-reassign", timeout=10000)
        checks.check(("Reassign ticket " + ticket_id) in page.locator("#modal-content .modal-title").inner_text(),
                     "monitor: Reassign opens the same dialog for the ticket")
        page.click("#form-modal-team-reassign button[data-close-modal]")
        sign_out(page)

        # --- 6. the new holder is a plain technician ------------------------------------------------------------------------
        sign_in(page, base, target["email"])
        page.click("button.nav-tab[data-target=console-technician]")
        own = page.locator("#tech-queue-container [data-ticket-card='%s']" % ticket_id)
        own.wait_for(timeout=15000)
        checks.check(own.locator(".btn-tech-reassign").count() == 0, "technician: the same ticket has no Reassign button")
        sign_out(page)

        checks.check(not errors, "browser: no console error and no failed request %s" % errors)
        browser.close()


if __name__ == "__main__":
    main()
