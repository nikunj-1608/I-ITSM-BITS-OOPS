#!/usr/bin/env python3
"""Real-browser check for ADR-050: the rebuilt Employee Portal tab.

Usage (application running and seeded; Python with Playwright and Microsoft Edge installed):

    mvn -o -Dmaven.test.skip=true spring-boot:run
    python scripts/live-checks/employee_portal_browser.py [--base http://localhost:8085] [--headed]

The employee signs in through the real login form and does everything with real clicks; the technician side
(acknowledge, start, request info, resolve) is driven through the API by the technician the ticket was assigned to.

  1  a quick-fill chip fills the form; the ticket is submitted, appears in My Active Tickets and is the featured ticket
  2  Live Ticket Tracking follows the public status (step and "N% complete") at every stage
  3  "Provide info" appears only when the technician asked (allowedActions), the KPI "Action required" counts it,
     and the reply puts the ticket back in progress
  4  "Reject fix" reopens a resolved ticket ("Reopened 1 time(s)"); "Confirm Fixed" verifies and rates it (CLOSED)
  5  the sub-views filter (active vs history), the badges and the history button count, Refresh keeps the selection
  6  a phone-width viewport has no horizontal scroll
  7  no priority, P1..P4 or SLA text anywhere on the employee's page (outside the privacy banner that names them),
     and no console error or failed request

Exit code 0 when every check passes, 1 otherwise.
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
ADMIN = "admin@nimbusfinserve.com"
EMPLOYEE = "employee@nimbusfinserve.com"
FALLBACK_TECH_ID = "TECH-NET-1"
FAILURES = []


# ---------------------------------------------------------------------------------------------------------- API helpers
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


def check(ok, text):
    print("%-4s %s" % ("ok" if ok else "FAIL", text))
    if not ok:
        FAILURES.append(text)
    return ok


def words(rng, n):
    return " ".join("".join(rng.choice(string.ascii_lowercase) for _ in range(8)) for _ in range(n))


class Api:
    def __init__(self, base):
        self.base = base
        self.admin = login(base, ADMIN)
        self.employee = login(base, EMPLOYEE)
        self.tech_token = None

    def detail(self, ticket_id):
        return call(self.base, "GET", "/api/admin/incidents/" + ticket_id, self.admin)[1] or {}

    def my_tickets(self):
        return call(self.base, "GET", "/api/employee/incidents", self.employee)[1] or []

    def wait_for(self, ticket_id, predicate, what, timeout=20):
        deadline = time.time() + timeout
        last = None
        while time.time() < deadline:
            last = self.detail(ticket_id)
            if last and predicate(last):
                return last
            time.sleep(0.4)
        check(False, "%s (last: status=%s assignee=%s)" % (what, last.get("status"), last.get("assigneeId")))
        return last

    def technician_for(self, ticket_id):
        """The technician the ticket was assigned to (the administrator assigns one if the intake did not)."""
        detail = self.wait_for(ticket_id, lambda d: d.get("assigneeId"), "the ticket gets a technician", timeout=10)
        tech_id = (detail or {}).get("assigneeId")
        if not tech_id:
            call(self.base, "POST", "/api/admin/incidents/%s/assign" % ticket_id, self.admin,
                 {"technicianId": FALLBACK_TECH_ID})
            tech_id = FALLBACK_TECH_ID
        roster = call(self.base, "GET", "/api/admin/technicians", self.admin)[1] or []
        email = next(t["email"] for t in roster if t["id"] == tech_id)
        self.tech_token = login(self.base, email)
        return tech_id

    def tech(self, ticket_id, action, body=None):
        status, answer = call(self.base, "POST", "/api/technician/incidents/%s/%s" % (ticket_id, action),
                              self.tech_token, body if body is not None else {})
        if status != 200:
            sys.exit("FAIL: technician %s on %s returned HTTP %s %s" % (action, ticket_id, status, answer))

    def resolve(self, ticket_id):
        self.tech(ticket_id, "resolve", {"rootCause": "Gateway certificate expired",
                                         "stepsTaken": "Renewed the certificate and restarted the tunnel",
                                         "confirmedCategory": "NETWORK"})


# ------------------------------------------------------------------------------------------------------ browser helpers
class Watch:
    def __init__(self, page):
        self.errors = []
        page.on("console", lambda m: m.type == "error" and not m.text.startswith("Failed to load resource")
                and self.errors.append("console error: %s" % m.text))
        page.on("pageerror", lambda e: self.errors.append("page error: %s" % e))
        page.on("response", lambda r: r.status >= 400 and not r.url.endswith("/favicon.ico")
                and self.errors.append("HTTP %s %s" % (r.status, r.url)))


def sign_in(page, base, email):
    page.goto(base + "/")
    page.fill("#login-email", email)
    page.fill("#login-password", PASSWORD)
    page.click("#form-login button[type=submit]")
    page.wait_for_selector("#btn-logout", timeout=15000)


def refresh(page):
    page.click("#btn-refresh-emp-tickets")
    page.wait_for_timeout(900)


def featured_id(page):
    return page.locator("#emp-featured-id").inner_text() if page.locator("#emp-featured-id").count() else None


def tracking(page):
    return page.locator("#emp-tracking").inner_text()


def active_step(page):
    step = page.locator("#emp-tracking .emp-track-step.is-active")
    return int(step.get_attribute("data-step")) if step.count() else None


def featured_buttons(page):
    return sorted(c for c in ("btn-emp-provide-info", "btn-emp-verify", "btn-emp-reject")
                  if page.locator("#emp-featured ." + c).count())


def wait_status(page, api, ticket_id, public_status, timeout=15):
    """Refreshes the employee list until the ticket shows the public status (the API is the source of truth)."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        refresh(page)
        row = page.locator('#emp-tickets-container .btn-emp-select[data-id="%s"]' % ticket_id)
        badge = page.locator("#emp-featured .emp-featured-head .badge")
        if featured_id(page) == ticket_id and badge.count() and badge.inner_text().strip() == public_status.replace("_", " "):
            return True
        if row.count():
            row.click()
    return False


def submit_modal(page, form_id, fills):
    for selector, value in fills.items():
        page.fill(selector, value)
    page.click("#%s button[type=submit]" % form_id)
    page.wait_for_selector("#%s" % form_id, state="hidden", timeout=15000)
    page.wait_for_timeout(900)


def privacy_leaks(page):
    """Visible text of the page outside the privacy banner that names a priority or an SLA."""
    text = page.evaluate("""() => {
        const banner = document.getElementById('emp-privacy-banner');
        const all = document.body.innerText;
        return banner ? all.replace(banner.innerText, '') : all; }""")
    return sorted(set(m.group(0) for m in re.finditer(r"(?i)\bpriority\b|\bSLA\b|\bP[1-4]\b|target sla", text)))


def counts_match(page, api):
    tickets = api.my_tickets()
    history = sum(1 for t in tickets if t["status"] in ("RESOLVED", "CLOSED"))
    shown = (page.locator("#emp-count-active").inner_text(), page.locator("#emp-count-history").inner_text(),
             page.locator("#emp-history-button-count").inner_text(), page.locator("#emp-stat-total").inner_text())
    return shown == (str(len(tickets) - history), str(history), str(history), str(len(tickets))), shown


# -------------------------------------------------------------------------------------------------------------- scenario
def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default="http://localhost:8085")
    parser.add_argument("--headed", action="store_true")
    args = parser.parse_args()
    base = args.base
    api = Api(base)
    rng = random.Random()

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(channel="msedge", headless=not args.headed)
        page = browser.new_page(viewport={"width": 1366, "height": 900})
        watch = Watch(page)

        sign_in(page, base, EMPLOYEE)
        page.wait_for_timeout(1200)
        check(page.locator("#console-employee.active").count() == 1, "the employee lands on the Employee Portal")
        check("ENFORCED" in page.locator("#emp-privacy-banner").inner_text(), "the privacy banner shows ENFORCED")

        # ---- 1. Report Issue + template chip + submit ------------------------------------------------------------------
        page.click("#emp-nav-report")
        page.wait_for_timeout(500)
        check(page.evaluate("document.activeElement && document.activeElement.id") == "emp-inc-title",
              "Report Issue moves the keyboard focus to the title field")
        page.click(".template-chip[data-template=vpn]")
        check(page.input_value("#emp-inc-title").startswith("VPN tunnel"), "the VPN chip fills the title")
        unique = words(rng, 2)
        page.fill("#emp-inc-title", page.input_value("#emp-inc-title") + " " + unique)
        page.fill("#emp-inc-desc", page.input_value("#emp-inc-desc") + " Reference " + words(rng, 3) + ".")
        check(page.locator("#desc-char-count").inner_text().split(" / ")[0] == str(len(page.input_value("#emp-inc-desc"))),
              "the character counter follows the description")
        page.click("#btn-submit-incident")
        page.wait_for_function("() => !!document.getElementById('emp-featured-id')", timeout=20000)
        page.wait_for_timeout(800)
        created = next((t for t in api.my_tickets() if unique in t["title"]), None)
        if not created:
            sys.exit("FAIL: the submitted ticket is not in GET /api/employee/incidents")
        ticket = created["id"]
        check(featured_id(page) == ticket, "the new ticket is the featured ticket (%s)" % ticket)
        row = page.locator('#emp-tickets-container .btn-emp-select[data-id="%s"]' % ticket)
        check(row.count() == 1 and row.get_attribute("aria-pressed") == "true",
              "the new ticket is in My Active Tickets and marked selected")
        check(page.locator("#emp-featured .btn-emp-provide-info, #emp-featured .btn-emp-verify, "
                           "#emp-featured .btn-emp-reject").count() == 0,
              "no action button on a ticket the employee cannot act on")

        # ---- 2. assignment, timeline -----------------------------------------------------------------------------------
        api.technician_for(ticket)
        check(wait_status(page, api, ticket, "ASSIGNED"), "the ticket shows ASSIGNED")
        check(active_step(page) == 2 and "40% complete" in tracking(page), "timeline: step 2 Assigned, 40%% (got %s)" % active_step(page))
        check("Awaiting assignment" not in page.locator("#emp-featured").inner_text(), "Assigned to shows the technician")
        reported = page.locator('#emp-tracking .emp-track-step[data-step="1"]').inner_text()
        check("Incident raised" in reported, "timeline: Reported shows the creation entry and its time")

        # ---- 3. provide info ------------------------------------------------------------------------------------------
        api.tech(ticket, "acknowledge")
        api.tech(ticket, "start-investigation")
        api.tech(ticket, "request-info", {"responseText": "Which office are you working from today?"})
        check(wait_status(page, api, ticket, "IN_PROGRESS"), "the ticket shows IN PROGRESS")
        check(featured_buttons(page) == ["btn-emp-provide-info"], "only Provide info is offered (%s)" % featured_buttons(page))
        check("Which office" in page.locator("#emp-featured").inner_text(), "the featured card shows the technician's question")
        check(page.locator("#emp-stat-pending").inner_text() != "0", "the KPI Action required counts the ticket")
        check(active_step(page) == 3 and "60% complete" in tracking(page), "timeline: step 3 In Progress, 60%")
        page.click("#emp-featured .btn-emp-provide-info")
        submit_modal(page, "form-modal-provide-info", {"#modal-info-response": "The Frankfurt office, desk 4."})
        api.wait_for(ticket, lambda d: d["status"] == "IN_PROGRESS", "Provide info: the ticket is IN_PROGRESS again")
        check(featured_id(page) == ticket and featured_buttons(page) == [],
              "after the reply the selection is kept and no button is left")

        # ---- 4. reject, then confirm -----------------------------------------------------------------------------------
        api.resolve(ticket)
        check(wait_status(page, api, ticket, "RESOLVED"), "the resolved ticket shows RESOLVED")
        check(featured_buttons(page) == ["btn-emp-reject", "btn-emp-verify"],
              "Confirm Fixed and Reject fix are offered (%s)" % featured_buttons(page))
        check("Gateway certificate expired" in page.locator("#emp-featured").inner_text(), "the resolution text is shown")
        check(active_step(page) == 4 and "80% complete" in tracking(page), "timeline: step 4 Resolved, 80%")
        page.click("#emp-featured .btn-emp-reject")
        submit_modal(page, "form-modal-reject", {"#modal-reject-reason": "The tunnel still drops after lunch"})
        api.wait_for(ticket, lambda d: d["status"] == "IN_PROGRESS" and d["reopenCount"] >= 1, "Reject fix reopens the ticket")
        check(wait_status(page, api, ticket, "IN_PROGRESS") and "Reopened 1 time(s)" in tracking(page),
              "after Reject fix the tracker says Reopened 1 time(s)")

        api.resolve(ticket)
        check(wait_status(page, api, ticket, "RESOLVED"), "resolved again")
        page.click("#emp-featured .btn-emp-verify")
        submit_modal(page, "form-modal-verify", {"#modal-feedback-comment": "Works now, thanks"})
        api.wait_for(ticket, lambda d: d["status"] == "CLOSED", "Confirm Fixed verifies and closes the ticket")
        check(wait_status(page, api, ticket, "CLOSED"), "the ticket shows CLOSED")
        check(active_step(page) is None and "100% complete" in tracking(page)
              and page.locator('#emp-tracking .emp-track-step[data-step="5"].is-completed').count() == 1,
              "timeline: all five steps completed, 100%")
        check(featured_buttons(page) == [], "no button on a closed ticket")

        # ---- 5. sub-views, counts, refresh keeps the selection ---------------------------------------------------------
        page.click("#emp-nav-active")
        page.wait_for_timeout(300)
        check(row.count() == 0, "My Active Tickets no longer lists the closed ticket")
        check(page.locator("#emp-nav-active").get_attribute("aria-pressed") == "true", "the active sub-tab is pressed")
        page.click("#btn-emp-open-history")
        page.wait_for_timeout(300)
        check(page.locator("#emp-list-title").inner_text() == "My History", "the history button opens My History")
        check(row.count() == 1, "My History lists the closed ticket")
        statuses = page.eval_on_selector_all("#emp-tickets-container .btn-emp-select .badge",
                                             "els => els.map(e => e.textContent)")
        check(all(s in ("RESOLVED", "CLOSED") for s in statuses), "My History shows only resolved and closed tickets")
        ok, shown = counts_match(page, api)
        check(ok, "badges, history button and total match the API %s" % (shown,))
        row.click()
        refresh(page)
        check(featured_id(page) == ticket, "Refresh keeps the featured ticket")

        # ---- 7. privacy ------------------------------------------------------------------------------------------------
        leaks = privacy_leaks(page)
        check(not leaks, "no priority or SLA text on the employee's page outside the banner %s" % leaks)

        # ---- 6. phone width --------------------------------------------------------------------------------------------
        page.set_viewport_size({"width": 390, "height": 844})
        page.wait_for_timeout(600)
        overflow = page.evaluate("document.documentElement.scrollWidth - document.documentElement.clientWidth")
        check(overflow <= 0, "phone width: no horizontal scroll (overflow %spx)" % overflow)

        check(not watch.errors, "no console error or failed request %s" % watch.errors)
        browser.close()

    print("\n%d failure(s)" % len(FAILURES))
    sys.exit(1 if FAILURES else 0)


if __name__ == "__main__":
    main()
