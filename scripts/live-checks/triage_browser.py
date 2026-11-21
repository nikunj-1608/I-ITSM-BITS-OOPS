#!/usr/bin/env python3
"""Real-browser check for ADR-029 (wrong-domain workflow), run against a running application.

Usage (the application must be running and seeded; Python with Playwright and Microsoft Edge installed):

    python scripts/live-checks/triage_browser.py [--base http://localhost:8085] [--headed]

What it does
  1. Through the API: an employee reports a VPN ticket, the assigned technician acknowledges it and starts investigating.
  2. In the browser, as that technician (real login form): the ticket card offers "Flag Out-of-Scope" while IN_PROGRESS;
     the technician flags it with a reason; the card leaves the queue.
  3. In the browser, as the administrator (Admin tab, "Triage & Reclassification Queue"): the Triage queue shows exactly ONE card for the ticket, labelled "Flagged by
     technician", with the technician, the reason and the classifier's suggestion; the administrator rejects the request
     with a reason; the card disappears.
  4. Through the API: the ticket is ASSIGNED to the technician again, the technician holds its slot and the capacity
     report shows no new difference.
  5. Throughout: no browser console error and no failed API call that was not expected.

Exit code 0 when every check passes, 1 otherwise.
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
ADMIN = "admin@nimbusfinserve.com"
FLAG_REASON = "This is a database fault and not a network fault"
REJECT_REASON = "It is a VPN problem, the Network team owns it, please carry on"


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
    parser.add_argument("--headed", action="store_true", help="show the browser window")
    args = parser.parse_args()
    base = args.base
    rng = random.Random()
    checks = Checks()

    # --- 1. setup through the API ---------------------------------------------------------------------------------
    employee, admin = login(base, EMPLOYEE), login(base, ADMIN)
    status, report_before = call(base, "GET", "/api/admin/capacity/reconcile", admin)
    before = {"%s|%s|%s" % (d["kind"], d["technicianId"], d["incidentId"]) for d in report_before["discrepancies"]}
    status, ticket = call(base, "POST", "/api/employee/incidents", employee, {
        "title": "VPN gateway " + words(rng, 2),
        "description": "The vpn and the gateway " + words(rng, 14) + " vpn."})
    if status not in (200, 201):
        sys.exit("FAIL: cannot create the ticket (HTTP %s)" % status)
    ticket_id = ticket["id"]
    status, detail = call(base, "GET", "/api/admin/incidents/" + ticket_id, admin)
    technician_id = detail.get("assigneeId")
    if not technician_id:
        sys.exit("FAIL: the ticket was not auto-assigned; cannot run the scenario")
    status, roster = call(base, "GET", "/api/admin/technicians", admin)
    technician_email = next(t["email"] for t in roster if t["id"] == technician_id)
    technician = login(base, technician_email)
    call(base, "POST", "/api/technician/incidents/%s/acknowledge" % ticket_id, technician)
    status, _ = call(base, "POST", "/api/technician/incidents/%s/start-investigation" % ticket_id, technician)
    checks.check(status == 200, "setup: ticket %s is IN_PROGRESS with %s" % (ticket_id, technician_id))
    # a vague ticket the intake holds back for low confidence, to see the other kind of triage card
    status, vague = call(base, "POST", "/api/employee/incidents", employee, {
        "title": "Unusual obscure glitch " + words(rng, 1),
        "description": "The quantum flux in sector nine oscillates unpredictably " + words(rng, 6)})
    vague_id = vague["id"] if status in (200, 201) and vague else None

    errors = []
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(channel="msedge", headless=not args.headed)
        page = browser.new_page()
        # The browser logs every failed resource as a console error without the address, so failed requests are
        # counted through the response event instead; the page declares no favicon and its 404 is expected and quiet.
        page.on("console", lambda m: errors.append("console error: %s" % m.text)
                if m.type == "error" and not m.text.startswith("Failed to load resource") else None)
        page.on("pageerror", lambda e: errors.append("page error: %s" % e))
        page.on("response", lambda r: errors.append("HTTP %s %s" % (r.status, r.url))
                if r.status >= 400 and not r.url.endswith("/favicon.ico") else None)

        # --- 2. the technician flags the ticket he is investigating ---------------------------------------------------
        sign_in(page, base, technician_email)
        page.click("button.nav-tab[data-target=console-technician]")
        card = page.locator("#tech-queue-container [data-ticket-card]", has_text=ticket_id)
        card.first.wait_for(timeout=15000)
        if card.first.locator(".tp-details-btn").get_attribute("aria-expanded") != "true":
            card.first.locator(".tp-details-btn").click()
        flag_button = card.locator(".btn-tech-wrongdomain")
        checks.check(flag_button.count() == 1, "technician: an IN_PROGRESS card offers 'Flag Out-of-Scope'")
        flag_button.click()
        page.fill("#modal-flag-reason", FLAG_REASON)
        page.click("#form-modal-flag button[type=submit]")
        page.wait_for_selector("#form-modal-flag", state="hidden", timeout=15000)
        page.wait_for_function(
            "id => !document.querySelector('#tech-queue-container').innerText.includes(id)", arg=ticket_id, timeout=15000)
        checks.check(True, "technician: the flagged ticket left the queue")
        sign_out(page)

        # --- 3. the administrator sees one card and rejects the request ---------------------------------------------
        sign_in(page, base, ADMIN)
        page.click("button.nav-tab[data-target=console-admin]")
        page.click("button.admin-subtab[data-subtarget=admin-triage-panel]")
        page.wait_for_selector("#admin-triage-container .card", timeout=15000)
        cards = page.locator("#admin-triage-container .card", has_text=ticket_id)
        checks.check(cards.count() == 1, "admin: the ticket has exactly one triage card (found %d)" % cards.count())
        text = cards.first.inner_text()
        checks.check("flagged by technician" in text.lower(), "admin: the card says 'Flagged by technician'")
        checks.check(technician_id in text and FLAG_REASON in text, "admin: the card shows the technician and the reason")
        checks.check("Classifier suggests" in text, "admin: the card shows the classifier's suggestion")
        reject_button = cards.first.locator(".btn-triage-reject")
        checks.check(reject_button.count() == 1, "admin: the card offers 'Reject request'")
        vague_cards = page.locator("#admin-triage-container .card", has_text=vague_id) if vague_id else None
        if vague_cards is not None and vague_cards.count() == 1:
            vague_text = vague_cards.first.inner_text().lower()
            checks.check("low confidence" in vague_text and "flagged by technician" not in vague_text
                         and vague_cards.first.locator(".btn-triage-reject").count() == 0
                         and vague_cards.first.locator(".btn-triage-resolve").count() == 1,
                         "admin: a low-confidence card says 'Low confidence', has no 'Reject request' and offers 'Reclassify'")
        else:
            print("note: the intake did not hold back the vague ticket, so the low-confidence card was not checked")
        reject_button.click()
        page.fill("#modal-reject-reassignment-reason", REJECT_REASON)
        page.click("#form-modal-reject-reassignment button[type=submit]")
        page.wait_for_selector("#form-modal-reject-reassignment", state="hidden", timeout=15000)
        page.wait_for_function(
            "id => !document.querySelector('#admin-triage-container').innerText.includes(id)", arg=ticket_id, timeout=15000)
        checks.check(True, "admin: the rejected request left the triage queue")
        browser.close()

    # --- 4. the result through the API ------------------------------------------------------------------------------
    status, after = call(base, "GET", "/api/admin/incidents/" + ticket_id, admin)
    checks.check(after.get("status") == "ASSIGNED" and after.get("assigneeId") == technician_id,
                 "result: the ticket is ASSIGNED to %s again" % technician_id)
    notes = " ".join(n.get("noteText", "") for n in (after.get("notes") or []))
    checks.check("please carry on" in notes, "result: the administrator's reason is a note on the ticket")
    status, report_after = call(base, "GET", "/api/admin/capacity/reconcile", admin)
    added = {"%s|%s|%s" % (d["kind"], d["technicianId"], d["incidentId"]) for d in report_after["discrepancies"]} - before
    checks.check(not added, "result: no new capacity difference %s" % sorted(added))

    # --- 5. the browser saw no error --------------------------------------------------------------------------------
    checks.check(not errors, "browser: no console error and no failed request %s" % errors)

    if checks.failures:
        print("FAILED: %d check(s)" % len(checks.failures))
        sys.exit(1)
    print("OK: the wrong-domain workflow works in a real browser")


if __name__ == "__main__":
    main()
