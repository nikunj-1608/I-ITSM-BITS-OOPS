#!/usr/bin/env python3
"""Real-browser check for ADR-049 (admin search filters), run against a running application.

Usage (the application must be running and seeded; Python with Playwright and Microsoft Edge installed):

    python scripts/live-checks/admin_search_filters_browser.py [--base http://localhost:8085] [--headed]

What it does
  1. Through the API: the employee reports two tickets (a VPN fault A and a database fault B) that share a run-unique
     word; A's technician acknowledges A, so the two tickets differ in status.
  2. In the browser, as the administrator (real login form, Admin tab, Enterprise Incident Catalog), with the run's
     word in the search box: applies each filter alone (status, SLA state, service, technician, created from/to,
     category, priority), then all of them together. Every time it checks the table rows against the API's answer for
     the exact request the page sent, and against what the ticket data says must match.
  3. The service and technician dropdowns were filled from the API; the SLA column shows each ticket's SLA status.
  4. A "Created from" after "Created to" shows a message and sends no request; Clear empties every control and
     searches without parameters.
  5. Throughout: no browser console error and no failed request.
  6. The two tickets are resolved at the end, so they are no duplicate parents for the next run.

Exit code 0 when every check passes, 1 otherwise.
"""
import argparse
import datetime
import json
import random
import string
import sys
import urllib.error
import urllib.parse
import urllib.request

from playwright.sync_api import sync_playwright

PASSWORD = "Password123!"
EMPLOYEE = "employee@nimbusfinserve.com"
ADMIN = "admin@nimbusfinserve.com"
SEARCH = "/api/admin/incidents"


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


def word(rng):
    return "".join(rng.choice(string.ascii_lowercase) for _ in range(10))


class Checks:
    def __init__(self):
        self.failures = []
        self.count = 0

    def check(self, ok, text):
        self.count += 1
        print("%-4s %s" % ("ok" if ok else "FAIL", text))
        if not ok:
            self.failures.append(text)


def sign_in(page, base, email):
    page.goto(base + "/")
    page.fill("#login-email", email)
    page.fill("#login-password", PASSWORD)
    page.click("#form-login button[type=submit]")
    page.wait_for_selector("#btn-logout", timeout=15000)


def pick(page, container_id, values):
    """Selects the given values in a multi-select dropdown and closes it."""
    page.click("#%s .multiselect-trigger" % container_id)
    for value in values:
        page.click("#%s .multiselect-option[data-value='%s']" % (container_id, value))
    page.keyboard.press("Escape")


def reset_controls(page, marker):
    """Empties every filter in the browser, then types the run's word (no search is triggered)."""
    page.evaluate("""() => {
        [ADMIN_FILTERS.categories, ADMIN_FILTERS.priorities, ADMIN_FILTERS.statuses, ADMIN_FILTERS.slaStates]
            .forEach(f => f.clear());
        ['admin-filter-service', 'admin-filter-technician', 'admin-filter-from', 'admin-filter-to']
            .forEach(id => { document.getElementById(id).value = ''; });
    }""")
    page.fill("#admin-search-text", marker)


def search(page, trigger="#btn-admin-search"):
    """Clicks a search trigger; returns (query string the page sent, ticket ids in the table)."""
    with page.expect_response(lambda r: urllib.parse.urlparse(r.url).path == SEARCH) as info:
        page.click(trigger)
    response = info.value
    page.wait_for_function("() => !document.querySelector('#admin-incidents-tbody').innerText.includes('Searching')")
    ids = page.eval_on_selector_all("#admin-incidents-tbody .btn-admin-view-ticket",
                                    "els => els.map(e => e.getAttribute('data-id'))")
    return urllib.parse.urlparse(response.url).query, set(ids)


def api_ids(base, admin, query):
    status, body = call(base, "GET", SEARCH + ("?" + query if query else ""), admin)
    return status, {t["id"] for t in (body or [])}


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default="http://localhost:8085")
    parser.add_argument("--headed", action="store_true", help="show the browser window")
    args = parser.parse_args()
    base = args.base
    rng = random.Random()
    checks = Checks()
    marker = word(rng)

    # --- 1. setup through the API ---------------------------------------------------------------------------------
    employee, admin = login(base, EMPLOYEE), login(base, ADMIN)
    status, a = call(base, "POST", "/api/employee/incidents", employee, {
        "title": "VPN gateway timeouts " + marker,
        "description": "AnyConnect vpn tunnel drops on the office gateway with packet loss %s %s." % (word(rng), word(rng))})
    status_b, b = call(base, "POST", "/api/employee/incidents", employee, {
        "title": "Database replication lag " + marker,
        "description": "SQL queries time out on the database server, replication lag grows %s %s." % (word(rng), word(rng))})
    if status not in (200, 201) or status_b not in (200, 201):
        sys.exit("FAIL: cannot create the tickets (HTTP %s, %s)" % (status, status_b))
    status, roster = call(base, "GET", "/api/admin/technicians", admin)
    emails = {t["id"]: t["email"] for t in roster}
    a = call(base, "GET", SEARCH + "/" + a["id"], admin)[1]
    if not a.get("assigneeId"):
        sys.exit("FAIL: ticket A was not auto-assigned; cannot run the scenario")
    tech_a = login(base, emails[a["assigneeId"]])
    call(base, "POST", "/api/technician/incidents/%s/acknowledge" % a["id"], tech_a)
    a = call(base, "GET", SEARCH + "/" + a["id"], admin)[1]
    b = call(base, "GET", SEARCH + "/" + b["id"], admin)[1]
    print("setup: A=%s %s/%s/%s/%s  B=%s %s/%s/%s/%s  word=%s" % (
        a["id"], a["status"], a["slaTracker"]["status"], a.get("serviceId"), a.get("assigneeId"),
        b["id"], b["status"], b["slaTracker"]["status"], b.get("serviceId"), b.get("assigneeId"), marker))
    ours = {a["id"], b["id"]}
    today = datetime.date.today()

    errors = []
    try:
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(channel="msedge", headless=not args.headed)
            page = browser.new_page()
            page.on("console", lambda m: errors.append("console error: %s" % m.text)
                    if m.type == "error" and not m.text.startswith("Failed to load resource") else None)
            page.on("pageerror", lambda e: errors.append("page error: %s" % e))
            page.on("response", lambda r: errors.append("HTTP %s %s" % (r.status, r.url))
                    if r.status >= 400 and not r.url.endswith("/favicon.ico") else None)

            sign_in(page, base, ADMIN)
            page.click("button.nav-tab[data-target=console-admin]")
            page.wait_for_function("() => document.querySelector('#admin-filter-service').options.length > 1"
                                   " && document.querySelector('#admin-filter-technician').options.length > 1",
                                   timeout=15000)
            services = page.eval_on_selector_all("#admin-filter-service option", "els => els.map(e => e.value)")
            techs = page.eval_on_selector_all("#admin-filter-technician option", "els => els.map(e => e.value)")
            checks.check(services[0] == "" and a.get("serviceId") in services,
                         "dropdown: the service list is filled from the API (%d services)" % (len(services) - 1))
            checks.check(techs[0] == "" and a["assigneeId"] in techs,
                         "dropdown: the technician list is filled from the API (%d technicians)" % (len(techs) - 1))

            def scenario(name, apply, must_have, must_not_have):
                reset_controls(page, marker)
                apply()
                query, shown = search(page)
                api_status, expected = api_ids(base, admin, query)
                checks.check(api_status == 200 and shown == expected,
                             "%s: table rows equal the API answer for '%s' (%s)" % (name, query, sorted(shown)))
                checks.check(must_have <= shown and not (must_not_have & shown),
                             "%s: shows %s, hides %s" % (name, sorted(must_have), sorted(must_not_have)))

            # --- 2. every filter alone, then all together ---------------------------------------------------------
            scenario("text only", lambda: None, ours, set())
            scenario("status", lambda: pick(page, "multiselect-status", [a["status"]]),
                     {a["id"]}, {b["id"]} if b["status"] != a["status"] else set())
            scenario("status (two values)", lambda: pick(page, "multiselect-status", sorted({a["status"], b["status"]})),
                     ours, set())
            scenario("SLA state", lambda: pick(page, "multiselect-sla", [a["slaTracker"]["status"]]), {a["id"]},
                     {b["id"]} if b["slaTracker"]["status"] != a["slaTracker"]["status"] else set())
            scenario("SLA state MET", lambda: pick(page, "multiselect-sla", ["MET"]), set(), ours)
            scenario("service", lambda: page.select_option("#admin-filter-service", a["serviceId"]), {a["id"]},
                     {b["id"]} if b.get("serviceId") != a.get("serviceId") else set())
            scenario("technician", lambda: page.select_option("#admin-filter-technician", a["assigneeId"]), {a["id"]},
                     {b["id"]} if b.get("assigneeId") != a["assigneeId"] else set())
            scenario("created today", lambda: (page.fill("#admin-filter-from", today.isoformat()),
                                               page.fill("#admin-filter-to", today.isoformat())), ours, set())
            yesterday = (today - datetime.timedelta(days=1)).isoformat()
            scenario("created yesterday", lambda: (page.fill("#admin-filter-from", yesterday),
                                                   page.fill("#admin-filter-to", yesterday)), set(), ours)
            scenario("category", lambda: pick(page, "multiselect-category", [a["category"]]), {a["id"]},
                     {b["id"]} if b["category"] != a["category"] else set())
            priority_a = a["priorityDecision"]["priority"]
            scenario("priority", lambda: pick(page, "multiselect-priority", [priority_a]), {a["id"]},
                     {b["id"]} if b["priorityDecision"]["priority"] != priority_a else set())

            def all_of_a():
                pick(page, "multiselect-status", [a["status"]])
                pick(page, "multiselect-sla", [a["slaTracker"]["status"]])
                pick(page, "multiselect-category", [a["category"]])
                pick(page, "multiselect-priority", [priority_a])
                page.select_option("#admin-filter-service", a["serviceId"])
                page.select_option("#admin-filter-technician", a["assigneeId"])
                page.fill("#admin-filter-from", today.isoformat())
                page.fill("#admin-filter-to", today.isoformat())
            scenario("all filters together", all_of_a, {a["id"]}, {b["id"]})

            # --- 3. the SLA column shows the ticket's SLA status ------------------------------------------------
            reset_controls(page, marker)
            search(page)
            row = page.locator("#admin-incidents-tbody tr", has=page.locator(".btn-admin-view-ticket[data-id='%s']" % a["id"]))
            sla_cell = row.locator("td").nth(5).inner_text().strip()
            headers = page.eval_on_selector_all("#admin-catalog-panel thead th", "els => els.map(e => e.innerText.trim())")
            checks.check(len(headers) == 9 and headers[5] == "SLA" and sla_cell == a["slaTracker"]["status"],
                         "table: 9 columns, the SLA column shows %s for A (cell '%s')" % (a["slaTracker"]["status"], sla_cell))

            # --- 4. reversed dates and Clear --------------------------------------------------------------------
            reset_controls(page, marker)
            page.fill("#admin-filter-from", today.isoformat())
            page.fill("#admin-filter-to", yesterday)
            sent = []
            listener = lambda r: sent.append(r.url) if urllib.parse.urlparse(r.url).path == SEARCH else None
            page.on("request", listener)
            page.click("#btn-admin-search")
            toast = page.locator("text=must not be after")
            toast.first.wait_for(timeout=5000)
            page.wait_for_timeout(500)
            page.remove_listener("request", listener)
            checks.check(toast.count() >= 1 and not sent,
                         "dates: 'Created from' after 'Created to' shows a message and sends no request (%d sent)" % len(sent))

            pick(page, "multiselect-status", [a["status"]])
            pick(page, "multiselect-sla", ["ON_TRACK"])
            page.select_option("#admin-filter-service", a["serviceId"])
            page.select_option("#admin-filter-technician", a["assigneeId"])
            query, shown = search(page, "#btn-admin-clear-filters")
            state = page.evaluate("""() => ({
                text: document.getElementById('admin-search-text').value,
                selected: [ADMIN_FILTERS.categories, ADMIN_FILTERS.priorities, ADMIN_FILTERS.statuses,
                           ADMIN_FILTERS.slaStates].map(f => f.getSelected().length),
                others: ['admin-filter-service', 'admin-filter-technician', 'admin-filter-from', 'admin-filter-to']
                    .map(id => document.getElementById(id).value)})""")
            checks.check(query == "" and state["text"] == "" and state["selected"] == [0, 0, 0, 0]
                         and state["others"] == ["", "", "", ""] and ours <= shown,
                         "clear: every control is empty and the search runs without parameters (%d rows)" % len(shown))
            browser.close()

        # --- 5. the browser saw no error ----------------------------------------------------------------------
        checks.check(not errors, "browser: no console error and no failed request %s" % errors)
    finally:
        # --- 6. resolve the two tickets ----------------------------------------------------------------------
        for ticket in (a, b):
            if ticket.get("assigneeId"):
                tech = login(base, emails[ticket["assigneeId"]])
                for step in ("acknowledge", "start-investigation"):
                    call(base, "POST", "/api/technician/incidents/%s/%s" % (ticket["id"], step), tech)
                call(base, "POST", "/api/technician/incidents/%s/resolve" % ticket["id"], tech, {
                    "rootCause": "Closed by the live check", "stepsTaken": "None", "preventiveAction": "None",
                    "confirmedCategory": ticket["category"]})

    if checks.failures:
        print("FAILED: %d of %d check(s)" % (len(checks.failures), checks.count))
        sys.exit(1)
    print("OK: %d of %d checks, the admin search filters work in a real browser" % (checks.count, checks.count))


if __name__ == "__main__":
    main()
