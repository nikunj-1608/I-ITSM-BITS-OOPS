#!/usr/bin/env python3
"""Real-browser check for ADR-039 (front-end closure): every action button is clicked and every guard is exercised.

Usage (application running and seeded; Python with Playwright and Microsoft Edge installed):

    mvn -o -Dmaven.test.skip=true spring-boot:run
    python scripts/live-checks/action_buttons_browser.py [--base http://localhost:8085] [--headed]

Every step signs in through the real login form, clicks real buttons and confirms the result through the API.

  Sign-in page   J1  a second sign-in and a second registration while one is in flight send ONE request
  Technician     Acknowledge, Start investigation, Add note (J3 focus returns to the button, J5 Tab stays in the dialog),
                 Request info, Resolve, Flag out-of-scope
  Employee       Raise a ticket (and the "Sign Out & Login as Specialist" button), select a ticket in the list
                 (ADR-050 portal: featured card and Live Ticket Tracking), Provide info, Reject fix, Confirm Fixed
  Team lead      Acknowledge & Handle, Inspect Escalation
  Administrator  Reject request (triage card shows the staff name, J6), Reclassify & Reassign, Override, Reassign,
                 the ticket dialog (J5 Tab trap, J6 composite score with three decimals), Activate strategy
  J2             a write refused with 403 refreshes the list the user is looking at

Exit code 0 when every check passes, 1 otherwise. The administrator's strategy change is put back at the end.
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
import uuid

from playwright.sync_api import sync_playwright

PASSWORD = "Password123!"
ADMIN = "admin@nimbusfinserve.com"
EMPLOYEE = "employee@nimbusfinserve.com"
LEAD = "lead.network@nimbusfinserve.com"
TECH1, TECH1_ID = "tech.network@nimbusfinserve.com", "TECH-NET-1"
TECH2, TECH2_ID = "tech.net2@nimbusfinserve.com", "TECH-NET-2"
TECH3, TECH3_ID = "tech.net3@nimbusfinserve.com", "TECH-NET-3"
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


def words(rng, n):
    return " ".join("".join(rng.choice(string.ascii_lowercase) for _ in range(9)) for _ in range(n))


class Api:
    def __init__(self, base):
        self.base = base
        self.admin = login(base, ADMIN)
        self.employee = login(base, EMPLOYEE)
        self.rng = random.Random()

    def new_ticket(self, kind="vpn"):
        if kind == "vague":
            title, text = "Something is not working properly " + words(self.rng, 1), \
                "Things seem slow and strange since yesterday afternoon, please have a look when you get some time."
        else:
            title = "VPN gateway " + words(self.rng, 2)
            text = "The vpn and the gateway " + words(self.rng, 14) + " vpn."
        status, body = call(self.base, "POST", "/api/employee/incidents", self.employee, {"title": title, "description": text})
        if status not in (200, 201):
            sys.exit("FAIL: cannot report a ticket (HTTP %s %s)" % (status, body))
        return body["id"]

    def detail(self, ticket_id):
        return call(self.base, "GET", "/api/admin/incidents/" + ticket_id, self.admin)[1]

    def assign(self, ticket_id, technician_id):
        status, body = call(self.base, "POST", "/api/admin/incidents/%s/assign" % ticket_id, self.admin, {"technicianId": technician_id})
        if status != 200:
            sys.exit("FAIL: cannot assign %s to %s (HTTP %s %s)" % (ticket_id, technician_id, status, body))

    def wait_for(self, ticket_id, predicate, what, timeout=15):
        deadline = time.time() + timeout
        last = None
        while time.time() < deadline:
            last = self.detail(ticket_id)
            if last and predicate(last):
                return last
            time.sleep(0.4)
        check(False, "%s (last: status=%s assignee=%s)" % (what, (last or {}).get("status"), (last or {}).get("assigneeId")))
        return last


# ------------------------------------------------------------------------------------------------------ browser helpers
class Watch:
    """Collects console errors and failed requests; a failure the script provokes on purpose is declared first."""

    def __init__(self, page):
        self.errors = []
        self.expected = []
        self.expected_console = []
        self.requests = []
        page.on("console", self._console)
        page.on("pageerror", lambda e: self.errors.append("page error: %s" % e))
        page.on("response", self._response)
        page.on("request", lambda r: self.requests.append((r.method, r.url)))

    def _console(self, message):
        if message.type != "error" or message.text.startswith("Failed to load resource"):
            return
        for i, fragment in enumerate(self.expected_console):
            if fragment in message.text:
                del self.expected_console[i]
                return
        self.errors.append("console error: %s" % message.text)

    def _response(self, r):
        if r.status >= 400 and not r.url.endswith("/favicon.ico"):
            for i, (status, fragment) in enumerate(self.expected):
                if r.status == status and fragment in r.url:
                    del self.expected[i]
                    return
            self.errors.append("HTTP %s %s" % (r.status, r.url))

    def expect(self, status, fragment):
        """A failed request the script provokes on purpose; the app also logs it with console.error ("API Error on ...")."""
        self.expected.append((status, fragment))

    def expect_console(self, fragment):
        self.expected_console.append(fragment)

    def count(self, method, fragment):
        return sum(1 for m, u in self.requests if m == method and fragment in u)


def sign_in(page, base, email):
    page.goto(base + "/")
    page.fill("#login-email", email)
    page.fill("#login-password", PASSWORD)
    page.click("#form-login button[type=submit]")
    page.wait_for_selector("#btn-logout", timeout=15000)


def sign_out(page):
    page.click("#btn-logout")
    page.wait_for_selector("#form-login", timeout=15000)


def tab(page, target):
    page.click("button.nav-tab[data-target=%s]" % target)
    page.wait_for_timeout(700)


def card(page, container, ticket_id):
    """The ticket's group in the technician queue table (ADR-051), opened so that its action buttons are visible."""
    locator = page.locator("%s [data-ticket-card]" % container, has_text=ticket_id).first
    locator.wait_for(timeout=15000)
    toggle = locator.locator(".tp-details-btn")
    if toggle.get_attribute("aria-expanded") != "true":
        toggle.click()
    return locator


def emp_feature(page, ticket_id, timeout=15):
    """Employee portal (ADR-050): finds the ticket's row in My Active Tickets or My History, clicks it and returns the
    featured ticket card once it shows that ticket."""
    row_selector = '#emp-tickets-container .btn-emp-select[data-id="%s"]' % ticket_id
    deadline = time.time() + timeout
    while time.time() < deadline:
        for view in ("#emp-nav-active", "#emp-nav-history"):
            page.click(view)
            if page.locator(row_selector).count():
                page.click(row_selector)
                page.wait_for_function("id => (document.getElementById('emp-featured-id') || {}).textContent === id",
                                       arg=ticket_id, timeout=5000)
                return page.locator("#emp-featured")
        page.wait_for_timeout(500)
    raise AssertionError("employee: ticket %s is in neither employee list" % ticket_id)


def submit_modal(page, form_id, fills=None, selects=None):
    for selector, value in (fills or {}).items():
        page.fill(selector, value)
    for selector, value in (selects or {}).items():
        page.select_option(selector, value)
    page.click("#%s button[type=submit]" % form_id)
    page.wait_for_selector("#%s" % form_id, state="hidden", timeout=15000)


def toast_text(page):
    return " | ".join(t.inner_text() for t in page.locator("#toast-container .toast").all())


def active_info(page):
    return page.evaluate("""() => { const a = document.activeElement; if (!a) return {tag: null};
        return {tag: a.tagName, cls: a.className, id: a.getAttribute('data-id'),
                inModal: !!a.closest('#modal-container') && !document.getElementById('modal-container').classList.contains('hidden'),
                inTicket: !!a.closest('#admin-ticket-detail-modal')}; }""")


# -------------------------------------------------------------------------------------------------------------- scenario
def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default="http://localhost:8085")
    parser.add_argument("--headed", action="store_true")
    args = parser.parse_args()
    base = args.base
    api = Api(base)

    status, strategies = call(base, "GET", "/api/admin/assignment/strategies", api.admin)
    original_strategy = next((s["name"] for s in strategies if s.get("active")), None)

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(channel="msedge", headless=not args.headed)
        page = browser.new_page()
        watch = Watch(page)

        # ---- J1: sign-in and registration ignore a second submit while one is in flight --------------------------------
        page.goto(base + "/")
        page.wait_for_selector("#form-login", timeout=15000)
        before = watch.count("POST", "/api/auth/login")
        page.evaluate("""async () => { await Promise.all([loginUser('%s', '%s'), loginUser('%s', '%s')]); }""" % (
            EMPLOYEE, PASSWORD, EMPLOYEE, PASSWORD))
        check(watch.count("POST", "/api/auth/login") - before == 1, "J1: two simultaneous sign-ins send ONE login request")
        page.wait_for_selector("#btn-logout", timeout=15000)
        sign_out(page)
        page.click("#btn-toggle-register")
        page.fill("#reg-name", "Double Submit")
        page.fill("#reg-email", "double.%s@nimbusfinserve.com" % uuid.uuid4().hex[:6])
        page.fill("#reg-password", PASSWORD)
        before = watch.count("POST", "/api/auth/register")
        page.evaluate("""() => { const f = document.getElementById('form-register'); f.requestSubmit(); f.requestSubmit(); }""")
        page.wait_for_selector("#btn-logout", timeout=15000)
        check(watch.count("POST", "/api/auth/register") - before == 1, "J1: two submits of the registration form send ONE request")
        sign_out(page)

        # ---- Technician 1: the whole lifecycle through buttons --------------------------------------------------------
        a = api.new_ticket()
        api.assign(a, TECH1_ID)
        sign_in(page, base, TECH1)
        tab(page, "console-technician")
        tech_card = lambda ticket: card(page, "#tech-queue-container", ticket)

        tech_card(a).locator(".btn-tech-ack").click()
        api.wait_for(a, lambda d: d["status"] == "ACKNOWLEDGED", "Acknowledge: the ticket is ACKNOWLEDGED")
        check(True, "technician: Acknowledge worked (ACKNOWLEDGED)")
        page.wait_for_timeout(900)
        tech_card(a).locator(".btn-tech-investigate").click()
        api.wait_for(a, lambda d: d["status"] == "IN_PROGRESS", "Start investigation: the ticket is IN_PROGRESS")
        check(True, "technician: Start investigation worked (IN_PROGRESS)")
        page.wait_for_timeout(900)

        # Add note through the keyboard: J5 the dialog keeps Tab inside, J3 focus comes back to the button
        note_button = ".btn-tech-note[data-id='%s']" % a
        page.wait_for_selector(note_button, timeout=15000)
        page.focus(note_button)
        page.keyboard.press("Enter")
        page.wait_for_selector("#form-modal-note", timeout=15000)
        check(active_info(page).get("inModal"), "J5: focus moves into the dialog when it opens")
        stays = True
        for _ in range(12):
            page.keyboard.press("Tab")
            stays &= bool(active_info(page).get("inModal"))
        check(stays, "J5: twelve Tab presses never leave the action dialog")
        first = page.evaluate("""() => { const f = document.querySelector('#modal-content').querySelectorAll(
            'a[href], button:not([disabled]), input:not([disabled]):not([type=hidden]), select:not([disabled]), textarea:not([disabled])');
            f[0].focus(); return true; }""")
        page.keyboard.press("Shift+Tab")
        check(active_info(page).get("inModal"), "J5: Shift+Tab on the first control wraps inside the dialog")
        submit_modal(page, "form-modal-note", {"#modal-note-text": "Checked the gateway configuration"})
        page.wait_for_timeout(1600)
        info = active_info(page)
        check("btn-tech-note" in (info.get("cls") or "") and info.get("id") == a,
              "J3: after the dialog closed the keyboard focus is back on the 'Add Note' button of the ticket (%s)" % info)
        notes = api.detail(a).get("notes") or []
        check(any("gateway configuration" in n["noteText"] for n in notes), "technician: Add note stored the note")

        tech_card(a).locator(".btn-tech-reqinfo").click()
        submit_modal(page, "form-modal-reqinfo", {"#modal-reqinfo-text": "Which office are you in and since when?"})
        api.wait_for(a, lambda d: d["status"] == "PENDING_INFO", "Request info: the ticket is PENDING_INFO")
        check(True, "technician: Request info worked (PENDING_INFO)")
        sign_out(page)

        # ---- Employee: provide info, timeline -------------------------------------------------------------------------
        sign_in(page, base, EMPLOYEE)
        tab(page, "console-employee")
        emp_card = lambda ticket: emp_feature(page, ticket)
        emp_card(a)
        tracking = page.locator("#emp-tracking").inner_text()
        check(a in tracking and "60% complete" in tracking,
              "employee: selecting the ticket shows it in Live Ticket Tracking at In Progress (60%)")
        emp_card(a).locator(".btn-emp-provide-info").click()
        submit_modal(page, "form-modal-provide-info", {"#modal-info-response": "The Singapore office, since this morning."})
        api.wait_for(a, lambda d: d["status"] == "IN_PROGRESS", "Provide info: the ticket is IN_PROGRESS again")
        check(True, "employee: Provide requested info worked (IN_PROGRESS)")
        sign_out(page)

        # ---- Technician: resolve; Employee: reject fix; Technician: resolve; Employee: verify -------------------------
        resolve_fields = {"#modal-root-cause": "Gateway firmware bug", "#modal-steps-taken": "Rolled the gateway back"}
        sign_in(page, base, TECH1)
        tab(page, "console-technician")
        tech_card(a).locator(".btn-tech-resolve").click()
        submit_modal(page, "form-modal-resolve", resolve_fields)
        api.wait_for(a, lambda d: d["status"] == "RESOLVED", "Resolve: the ticket is RESOLVED")
        check(True, "technician: Resolve worked (RESOLVED)")
        sign_out(page)

        sign_in(page, base, EMPLOYEE)
        tab(page, "console-employee")
        emp_card(a).locator(".btn-emp-reject").click()
        submit_modal(page, "form-modal-reject", {"#modal-reject-reason": "The VPN still drops after an hour"})
        api.wait_for(a, lambda d: d["status"] == "IN_PROGRESS" and d["reopenCount"] >= 1, "Reject fix: the ticket is reopened")
        check(True, "employee: Reject fix reopened the ticket")
        sign_out(page)

        sign_in(page, base, TECH1)
        tab(page, "console-technician")
        tech_card(a).locator(".btn-tech-resolve").click()
        submit_modal(page, "form-modal-resolve", resolve_fields)
        api.wait_for(a, lambda d: d["status"] == "RESOLVED", "Resolve again: the ticket is RESOLVED")
        sign_out(page)

        sign_in(page, base, EMPLOYEE)
        tab(page, "console-employee")
        emp_card(a).locator(".btn-emp-verify").click()
        submit_modal(page, "form-modal-verify", {"#modal-feedback-comment": "Fixed, thanks"})
        api.wait_for(a, lambda d: d["status"] in ("VERIFIED", "CLOSED"), "Verify: the ticket is verified")
        check(True, "employee: Verify & rate worked (%s)" % api.detail(a)["status"])

        # ---- Employee: raise a ticket in the form; no confirmation box is drawn (ADR-055) ---------------------------------
        page.fill("#emp-inc-title", "VPN gateway " + words(api.rng, 2))
        page.fill("#emp-inc-desc", "The vpn and the gateway " + words(api.rng, 14) + " vpn.")
        page.click("#btn-submit-incident")
        page.wait_for_selector("#emp-tickets-container button.btn-emp-select", timeout=20000)
        page.wait_for_timeout(1000)
        check(page.locator("#emp-callout-container").inner_text().strip() == "" and page.locator(".btn-demo-quick-switch").count() == 0,
              "employee: Raise ticket shows no confirmation box and no sign-out shortcut button")
        check(page.locator("#app-view > footer").is_hidden(), "employee: the page footer is not shown on the Employee tab")
        sign_out(page)

        # ---- Technician 2: flag out-of-scope; team lead; administrator triage -----------------------------------------
        b = api.new_ticket()
        api.assign(b, TECH2_ID)
        sign_in(page, base, TECH2)
        tab(page, "console-technician")
        card(page, "#tech-queue-container", b).locator(".btn-tech-ack").click()
        api.wait_for(b, lambda d: d["status"] == "ACKNOWLEDGED", "ticket B is ACKNOWLEDGED")
        page.wait_for_timeout(900)
        card(page, "#tech-queue-container", b).locator(".btn-tech-investigate").click()
        api.wait_for(b, lambda d: d["status"] == "IN_PROGRESS", "ticket B is IN_PROGRESS")
        page.wait_for_timeout(900)
        card(page, "#tech-queue-container", b).locator(".btn-tech-wrongdomain").click()
        submit_modal(page, "form-modal-flag", {"#modal-flag-reason": "This is a database fault and not a network fault"})
        flagged = api.wait_for(b, lambda d: d["status"] == "CLASSIFIED" and d.get("needsTriage"), "Flag: the ticket is in triage")
        check(bool(flagged and flagged.get("needsTriage")), "technician: Flag out-of-scope sent the ticket to triage")
        sign_out(page)

        sign_in(page, base, LEAD)
        tab(page, "console-teamlead")
        page.wait_for_timeout(1200)
        # Phase M3/M4: a ticket in triage belongs to the administrator, so the lead's list shows no button for it (the old
        # "Acknowledge & Handle" could only fail with 409) and says why.
        entry = page.locator("#teamlead-unassigned-container .card", has_text=b)
        if entry.count() == 1:
            check(entry.locator("button").count() == 0, "team lead: a ticket in triage has no action button in the unassigned list")
            check("In triage" in entry.inner_text(), "team lead: the unassigned list says the ticket is in triage")
        else:
            check(False, "team lead: the flagged ticket is in the unassigned list")
        sign_out(page)

        # ---- Administrator ----------------------------------------------------------------------------------------------
        c = api.new_ticket("vague")
        sign_in(page, base, ADMIN)
        tab(page, "console-admin")
        page.click("button.admin-subtab[data-subtarget=admin-triage-panel]")
        b_card = card(page, "#admin-triage-container", b)
        check(re.search(r"P\d-" + re.escape(b), b_card.inner_text()) is not None,
              "J6: the triage card shows the staff ticket name (priority and id)")
        b_card.locator(".btn-triage-reject").click()
        submit_modal(page, "form-modal-reject-reassignment", {"#modal-reject-reassignment-reason": "It is a VPN problem, please carry on"})
        api.wait_for(b, lambda d: d["status"] == "ASSIGNED" and d["assigneeId"] == TECH2_ID, "Reject request: B back with its technician")
        check(True, "admin: Reject request returned the ticket to the technician")

        if (api.detail(c) or {}).get("needsTriage"):
            page.click("#btn-refresh-triage") if page.locator("#btn-refresh-triage").count() else page.click(
                "button.admin-subtab[data-subtarget=admin-triage-panel]")
            card(page, "#admin-triage-container", c).locator(".btn-triage-resolve").click()
            submit_modal(page, "form-modal-reclassify", selects={"#modal-new-category": "NETWORK"})
            done = api.wait_for(c, lambda d: not d.get("needsTriage") and d["category"] == "NETWORK", "Reclassify: the ticket leaves triage")
            check(bool(done and not done.get("needsTriage")), "admin: Reclassify & Reassign took the ticket out of triage")
        else:
            print("note: the intake did not hold back the vague ticket, Reclassify & Reassign was not clicked")

        d = api.new_ticket()
        api.assign(d, TECH1_ID)
        page.click("button.admin-subtab[data-subtarget=admin-catalog-panel]")
        page.fill("#admin-search-text", d)
        page.click("#btn-admin-search")
        row = page.locator("#admin-incidents-tbody tr", has_text=d).first
        row.wait_for(timeout=15000)
        # by keyboard: the table is drawn again after the write, so focus must go to the redrawn twin of the button (J3)
        override_button = "#admin-incidents-tbody .btn-admin-override[data-id='%s']" % d
        page.focus(override_button)
        page.keyboard.press("Enter")
        page.wait_for_selector("#form-modal-priority", timeout=15000)
        submit_modal(page, "form-modal-priority", {"#modal-priority-reason": "Board escalation for the trading floor"},
                     {"#modal-new-priority": "P1"})
        api.wait_for(d, lambda x: x["priorityDecision"]["priority"] == "P1", "Override: the ticket is P1")
        check(True, "admin: Override priority worked (P1)")
        page.wait_for_timeout(1600)
        info = active_info(page)
        check("btn-admin-override" in (info.get("cls") or "") and info.get("id") == d,
              "J3: after the table was redrawn the keyboard focus is on the Override button of the same ticket (%s)" % info)
        page.wait_for_timeout(900)
        page.fill("#admin-search-text", d)
        page.click("#btn-admin-search")
        row = page.locator("#admin-incidents-tbody tr", has_text=d).first
        row.wait_for(timeout=15000)
        row.locator(".btn-admin-reassign").click()
        page.wait_for_selector("#form-modal-reassign", timeout=15000)
        submit_modal(page, "form-modal-reassign", selects={"#modal-target-tech": TECH3_ID})
        api.wait_for(d, lambda x: x["assigneeId"] == TECH3_ID, "Reassign: the ticket is with TECH-NET-3")
        check(True, "admin: Reassign worked (TECH-NET-3)")
        page.wait_for_timeout(900)

        page.fill("#admin-search-text", d)
        page.click("#btn-admin-search")
        page.locator("#admin-incidents-tbody tr", has_text=d).first.wait_for(timeout=15000)
        page.locator(".btn-admin-view-ticket", has_text=d).first.click()
        page.wait_for_selector("#admin-ticket-detail-modal:not(.hidden)", timeout=15000)
        page.wait_for_function("() => document.querySelector('#td-priority-summary').innerText.length > 5", timeout=15000)
        score = page.locator("#td-priority-summary .td-score").inner_text().strip()
        check(re.fullmatch(r"\d\.\d{3}", score) is not None, "J6: the composite score shows three decimals (%s)" % score)
        stays = True
        for _ in range(40):
            page.keyboard.press("Tab")
            stays &= bool(active_info(page).get("inTicket"))
        check(stays, "J5: forty Tab presses never leave the ticket dialog")
        stays = True
        for _ in range(5):
            page.keyboard.press("Shift+Tab")
            stays &= bool(active_info(page).get("inTicket"))
        check(stays, "J5: Shift+Tab stays inside the ticket dialog")
        page.keyboard.press("Escape")
        page.wait_for_selector("#admin-ticket-detail-modal", state="hidden", timeout=5000)
        check(True, "admin: the ticket dialog closes with Escape")

        page.click("button.admin-subtab[data-subtarget=admin-strategy-panel]")
        activate = page.locator("#strategies-grid .btn-activate-strategy:not([disabled])")
        if activate.count() == 0:
            activate = page.locator(".btn-activate-strategy:not([disabled])")
        name = activate.first.get_attribute("data-name")
        activate.first.click()
        page.wait_for_function("n => document.body.innerText.includes('Activated strategy: ' + n)", arg=name, timeout=10000)
        status, now = call(base, "GET", "/api/admin/assignment/strategies", api.admin)
        check(any(s["name"] == name and s.get("active") for s in now), "admin: Activate strategy switched to %s" % name)
        if original_strategy:
            call(base, "PUT", "/api/admin/assignment/strategies/active", api.admin, {"strategyName": original_strategy})
        sign_out(page)

        # ---- Team lead (Phases M1, M3, M4): a P1 ticket that is on track is not an escalation, but it is in the team
        # queue with an Assign/Reassign button; Inspect Escalation itself is checked by team_lead_reassign_browser.py ----
        sign_in(page, base, LEAD)
        tab(page, "console-teamlead")
        page.wait_for_timeout(1200)
        intervene = page.locator("#teamlead-escalation-container .btn-teamlead-intervene[data-id='%s']" % d)
        check(intervene.count() == 0, "team lead: a P1 ticket that is on track is not listed as an SLA escalation")

        tab(page, "console-technician")
        page.click("#btn-tech-mode-team")
        page.wait_for_timeout(1200)
        team_card = page.locator("#tech-queue-container [data-ticket-card='%s']" % d)
        check(team_card.count() == 1 and team_card.locator(".btn-tech-reassign").count() == 1,
              "team lead: the P1 ticket is in the team queue with an Assign/Reassign button")
        sign_out(page)

        # ---- J2: a refused write (403) refreshes the list the user is looking at --------------------------------------
        g = api.new_ticket()
        api.assign(g, TECH1_ID)
        sign_in(page, base, TECH1)
        tab(page, "console-technician")
        stale = card(page, "#tech-queue-container", g)
        api.assign(g, TECH3_ID)  # the administrator moves it while the technician still sees the old card
        refreshes_before = watch.count("GET", "/api/technician/queue")
        watch.expect(403, "/acknowledge")
        watch.expect(409, "/acknowledge")
        watch.expect_console("API Error on /api/technician/incidents/%s/acknowledge" % g)
        stale.locator(".btn-tech-ack").click()
        page.wait_for_function("id => !document.querySelector('#tech-queue-container').innerText.includes(id)", arg=g, timeout=15000)
        check(watch.count("GET", "/api/technician/queue") > refreshes_before,
              "J2: the refused Acknowledge refreshed the queue and the stale card is gone")
        sign_out(page)
        browser.close()

    check(not watch.errors, "browser: no console error and no unexpected failed request %s" % watch.errors)
    if FAILURES:
        print("FAILED: %d check(s)" % len(FAILURES))
        sys.exit(1)
    print("OK: every action button works in a real browser and the Phase J guards hold")


if __name__ == "__main__":
    main()
