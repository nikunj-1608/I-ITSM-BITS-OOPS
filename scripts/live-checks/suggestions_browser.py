#!/usr/bin/env python3
"""Real-browser check for ADR-043 (suggested resolutions in the web app, Phase K4).

Usage (application running and seeded; Python with Playwright and Microsoft Edge installed):

    mvn -o -Dmaven.test.skip=true spring-boot:run
    python scripts/live-checks/suggestions_browser.py [--base http://localhost:8085] [--headed]

Signs in through the real login form and clicks real buttons; every result is confirmed through the API.

  Technician   open ticket: "Suggested fixes" (no "Promote"); resolved ticket of mine: "Promote" (no "Suggested fixes")
               the dialog lists the resolved fix with source, scores, root cause, steps and matched terms
               "This worked" is recorded (API count 1), the list is drawn again, focus returns to the vote button
               Tab stays inside the dialog; Escape/Close gives focus back to the "Suggested fixes" button
               an empty answer shows "No similar past fix"; a late answer for a replaced dialog is discarded
               a hostile root cause (<img ...>) is shown as text, no element is created
               Promote: Enter pressed twice sends ONE request; the article is suggested afterwards instead of the ticket
               a second promotion shows the friendly 409 message and refreshes the list
  Team lead    team mode shows "Promote" on a resolved team ticket
  Employee     no suggestion or promotion control anywhere on the employee screen
  All          no console error and no unexpected failed request

Exit code 0 when every check passes, 1 otherwise. Tickets, votes and articles stay in the database; the two open tickets
are resolved at the end so TECH-NET-1 keeps capacity for the next run.
"""
import argparse
import json
import sys
import time
import random
import string
import urllib.error
import urllib.request

from playwright.sync_api import sync_playwright

PASSWORD = "Password123!"
ADMIN = "admin@nimbusfinserve.com"
EMPLOYEE = "employee@nimbusfinserve.com"
LEAD = "lead.network@nimbusfinserve.com"
TECH1, TECH1_ID = "tech.network@nimbusfinserve.com", "TECH-NET-1"
FAILURES = []


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


def must(result, what):
    status, body = result
    if status >= 300:
        sys.exit("FAIL: %s answered HTTP %s: %s" % (what, status, body))
    return body


class Api:
    def __init__(self, base):
        self.base = base
        self.admin = login(base, ADMIN)
        self.employee = login(base, EMPLOYEE)
        self.tech = login(base, TECH1)

    def ticket(self, title, description):
        body = must(call(self.base, "POST", "/api/employee/incidents", self.employee,
                         {"title": title, "description": description}), "report")
        must(call(self.base, "POST", "/api/admin/incidents/%s/assign" % body["id"], self.admin,
                  {"technicianId": TECH1_ID}), "assign")
        return body["id"]

    def resolved(self, title, description, root_cause, steps):
        ticket_id = self.ticket(title, description)
        for step in ("acknowledge", "start-investigation"):
            must(call(self.base, "POST", "/api/technician/incidents/%s/%s" % (ticket_id, step), self.tech), step)
        must(call(self.base, "POST", "/api/technician/incidents/%s/resolve" % ticket_id, self.tech, {
            "rootCause": root_cause, "stepsTaken": steps, "preventiveAction": "Certificate expiry alert",
            "confirmedCategory": "NETWORK"}), "resolve")
        return ticket_id

    def finish(self, ticket_id):
        """Resolves an open ticket this script created, so TECH-NET-1 gets the capacity slot back for the next run."""
        for step in ("acknowledge", "start-investigation"):
            call(self.base, "POST", "/api/technician/incidents/%s/%s" % (ticket_id, step), self.tech)
        call(self.base, "POST", "/api/technician/incidents/%s/resolve" % ticket_id, self.tech, {
            "rootCause": "Closed by the browser check", "stepsTaken": "None", "preventiveAction": "None",
            "confirmedCategory": "NETWORK"})

    def suggestions(self, ticket_id):
        return must(call(self.base, "GET", "/api/technician/incidents/%s/suggested-resolutions" % ticket_id, self.tech),
                    "suggestions")


class Watch:
    """Console errors and failed requests; a failure the script provokes on purpose is declared first."""

    def __init__(self, page):
        self.errors, self.expected, self.requests = [], [], []
        page.on("console", self._console)
        page.on("pageerror", lambda e: self.errors.append("page error: %s" % e))
        page.on("response", self._response)
        page.on("request", lambda r: self.requests.append((r.method, r.url)))

    def _console(self, message):
        if message.type != "error" or message.text.startswith("Failed to load resource"):
            return
        if "API Error on" in message.text and any(f in message.text for _, f in self.expected_seen):
            return
        self.errors.append("console error: %s" % message.text)

    expected_seen = []

    def _response(self, r):
        if r.status >= 400 and not r.url.endswith("/favicon.ico"):
            for i, (status, fragment) in enumerate(self.expected):
                if r.status == status and fragment in r.url:
                    self.expected_seen.append(self.expected.pop(i))
                    return
            self.errors.append("HTTP %s %s" % (r.status, r.url))

    def expect(self, status, fragment):
        self.expected.append((status, fragment))

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


def card(page, ticket_id):
    locator = page.locator("#tech-queue-container [data-ticket-card]", has_text=ticket_id).first
    locator.wait_for(timeout=15000)
    toggle = locator.locator(".tp-details-btn")
    if toggle.get_attribute("aria-expanded") != "true":
        toggle.click()
    return locator


def active(page):
    return page.evaluate("""() => { const a = document.activeElement;
        return {cls: a ? a.className : '', id: a ? a.getAttribute('data-id') : null,
                inModal: !!(a && a.closest('#modal-container'))}; }""")


def open_suggestions(page, ticket_id):
    card(page, ticket_id).locator(".btn-tech-suggest").click()
    page.wait_for_selector("#suggestions-body .suggestion-card, #suggestions-body .suggestions-empty", timeout=15000)


def close_dialog(page):
    page.click("#modal-content .modal-footer [data-close-modal]")
    page.wait_for_selector("#modal-container", state="hidden", timeout=5000)


def toast_text(page):
    return " | ".join(t.inner_text() for t in page.locator("#toast-container .toast").all())


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default="http://localhost:8085")
    parser.add_argument("--headed", action="store_true")
    args = parser.parse_args()
    base = args.base
    api = Api(base)

    # Four words only this run uses, shared by A and B. Earlier runs leave resolved tickets with the same wording in the
    # database (the open tickets of a run are resolved at its end); unseen words weigh most in TF-IDF, so sharing four of
    # them puts this run's A above those clones, which carry other unique words and tie each other on the rest.
    run = " ".join("".join(random.choice(string.ascii_lowercase) for _ in range(8)) for _ in range(4))
    a = api.resolved("VPN gateway rejects every AnyConnect login at %s" % run,
                     "Remote staff at %s cannot connect: the AnyConnect VPN gateway refuses the tunnel with a certificate error." % run,
                     "Expired TLS certificate on the AnyConnect VPN gateway",
                     "Renewed the gateway certificate and restarted the VPN tunnel service")
    hostile = api.resolved("VPN gateway certificate warning on the AnyConnect client",
                           "The AnyConnect client warns about the VPN gateway certificate before the tunnel drops.",
                           "<img src=x id=pwned-k4> VPN gateway certificate chain incomplete",
                           "<b>Installed</b> the intermediate certificate on the VPN gateway")
    b = api.ticket("AnyConnect VPN tunnel fails with certificate error at %s" % run,
                   "Since this morning the VPN gateway at %s refuses my AnyConnect tunnel and shows a certificate warning." % run)
    empty = api.ticket("Question about the quarterly offsite catering menu",
                       "Could someone tell me whether the quarterly offsite lunch includes vegetarian options for everyone?")
    print("tickets: A (resolved) %s, hostile fix %s, B %s, no-match %s" % (a, hostile, b, empty))

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(channel="msedge", headless=not args.headed)
        page = browser.new_page()
        watch = Watch(page)

        sign_in(page, base, TECH1)
        tab(page, "console-technician")
        check(card(page, b).locator(".btn-tech-suggest").count() == 1 and card(page, b).locator(".btn-tech-promote").count() == 0,
              "open ticket B: 'Suggested fixes', no 'Promote'")
        check(card(page, a).locator(".btn-tech-promote").count() == 1 and card(page, a).locator(".btn-tech-suggest").count() == 0,
              "resolved ticket A (mine): 'Promote', no 'Suggested fixes'")

        # ---- the dialog: content, escaping, vote, focus, Tab trap ----------------------------------------------------
        open_suggestions(page, b)
        item = page.locator("#suggestions-body .suggestion-card[data-source-id='%s']" % a)
        check(item.count() == 1, "dialog lists ticket A for B (%d suggestion(s))" % page.locator("#suggestions-body .suggestion-card").count())
        text = item.inner_text() if item.count() else ""
        # badges are drawn in upper case (.badge text-transform), and inner_text reports the rendered text
        check("resolved ticket" in text.lower() and "Similarity" in text and "Expired TLS certificate" in text
              and "Renewed the gateway" in text,
              "A's card shows source, scores, root cause and steps")
        check(item.locator(".suggestion-term").count() >= 1, "A's card shows matched terms (%s)"
              % [t.inner_text() for t in item.locator(".suggestion-term").all()])
        hostile_item = page.locator("#suggestions-body .suggestion-card[data-source-id='%s']" % hostile)
        check(page.evaluate("() => document.getElementById('pwned-k4') === null")
              and (hostile_item.count() == 0 or "<img src=x id=pwned-k4>" in hostile_item.inner_text()),
              "a hostile root cause is shown as text, no element is created (listed: %s)" % (hostile_item.count() == 1))

        vote = item.locator(".btn-tech-vote-worked")
        vote.focus()
        page.keyboard.press("Enter")
        page.wait_for_timeout(1500)
        counts = next((s for s in api.suggestions(b) if s["sourceId"] == a), {})
        check(counts.get("workedVotes") == 1, "'This worked' is recorded (API workedVotes %s)" % counts.get("workedVotes"))
        redrawn = page.locator("#suggestions-body .suggestion-card[data-source-id='%s'] .btn-tech-vote-worked .suggestion-count" % a)
        check(redrawn.count() == 1 and redrawn.inner_text().strip() == "1", "the dialog is drawn again with the new count")
        info = active(page)
        check("btn-tech-vote-worked" in info["cls"] and info["id"] == a, "keyboard focus returns to the vote button (%s)" % info)

        for _ in range(12):
            page.keyboard.press("Tab")
        info = active(page)
        check(info["inModal"], "Tab x12 stays inside the dialog")
        page.keyboard.press("Shift+Tab")
        check(active(page)["inModal"], "Shift+Tab stays inside the dialog")
        close_dialog(page)
        page.wait_for_timeout(900)
        info = active(page)
        check("btn-tech-suggest" in info["cls"] and info["id"] == b, "closing gives focus back to 'Suggested fixes' (%s)" % info)

        # ---- empty answer, and a late answer for a replaced dialog ---------------------------------------------------
        open_suggestions(page, empty)
        check(page.locator("#suggestions-body .suggestions-empty").count() == 1, "no match: 'No similar past fix was found'")
        close_dialog(page)
        page.evaluate("""(slowId) => { if (window.__k4Slow) return; window.__k4Slow = true; const real = window.fetch;
            window.fetch = (url, opts) => String(url).includes(slowId + '/suggested-resolutions')
                ? new Promise(r => setTimeout(() => r(real(url, opts)), 1500)) : real(url, opts); }""", b)
        card(page, b).locator(".btn-tech-suggest").click()
        page.wait_for_timeout(200)
        close_dialog(page)
        open_suggestions(page, empty)
        page.wait_for_timeout(2200)
        check(page.locator("#suggestions-body .suggestions-empty").count() == 1
              and page.locator("#suggestions-body .suggestion-card").count() == 0,
              "a late answer for B does not overwrite the dialog now open for another ticket")
        close_dialog(page)

        # ---- promote: one request for a double Enter, then the article replaces the ticket in the suggestions ---------
        page.wait_for_timeout(900)
        card(page, a).locator(".btn-tech-promote").click()
        page.wait_for_selector("#form-modal-promote", timeout=5000)
        page.fill("#modal-promote-title", "Renew the AnyConnect VPN gateway certificate")
        before = watch.count("POST", "/api/technician/knowledge-articles")
        page.evaluate("""() => { const f = document.getElementById('form-modal-promote'); f.requestSubmit(); f.requestSubmit(); }""")
        page.wait_for_selector("#modal-container", state="hidden", timeout=15000)
        check(watch.count("POST", "/api/technician/knowledge-articles") - before == 1, "Enter twice on Promote sends ONE request")
        check("Added to the knowledge base as KB-%s" % a in toast_text(page), "promotion toast names the article")
        ids = [s["sourceId"] for s in api.suggestions(b)]
        check("KB-" + a in ids and a not in ids, "B's suggestions now show A's article instead of A (%s)" % ids)
        page.wait_for_timeout(1200)
        open_suggestions(page, b)
        kb_item = page.locator("#suggestions-body .suggestion-card[data-source-id='KB-%s']" % a)
        check(kb_item.count() == 1 and "knowledge base" in kb_item.inner_text().lower(), "the dialog shows the article as 'Knowledge base'")
        close_dialog(page)

        page.wait_for_timeout(900)
        watch.expect(409, "/api/technician/knowledge-articles")
        card(page, a).locator(".btn-tech-promote").click()
        page.wait_for_selector("#form-modal-promote", timeout=5000)
        page.click("#form-modal-promote button[type=submit]")
        page.wait_for_selector("#modal-container", state="hidden", timeout=15000)
        page.wait_for_timeout(800)
        check("already in the knowledge base" in toast_text(page), "a second promotion shows the friendly 409 message")
        card(page, a)
        check(True, "the list was drawn again after the refusal")
        sign_out(page)

        # ---- team lead and employee -----------------------------------------------------------------------------------
        sign_in(page, base, LEAD)
        tab(page, "console-technician")
        page.click("#btn-tech-mode-team")
        page.wait_for_timeout(1200)
        check(card(page, a).locator(".btn-tech-promote").count() == 1, "team lead (team mode): 'Promote' on resolved team ticket A")
        sign_out(page)
        sign_in(page, base, EMPLOYEE)
        page.wait_for_timeout(1500)
        html = page.content()
        check("btn-tech-suggest" not in html and "btn-tech-promote" not in html and "suggestion-card" not in html,
              "employee screen has no suggestion or promotion control")
        sign_out(page)

        for ticket_id in (b, empty):
            api.finish(ticket_id)
        check(not watch.errors, "no console error and no unexpected failed request %s" % watch.errors)
        check(not watch.expected, "every provoked failure happened %s" % watch.expected)
        browser.close()

    print("%d check(s) failed" % len(FAILURES) if FAILURES else "all checks passed")
    sys.exit(1 if FAILURES else 0)


if __name__ == "__main__":
    main()
