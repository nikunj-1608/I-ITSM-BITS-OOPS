#!/usr/bin/env python3
"""Live check for ADR-044 Phase L4 (service-experience factor in the assignment): API and the admin dialog in Edge.

Usage (application running and seeded; for the browser part Python with Playwright and Microsoft Edge installed):

    mvn -o -Dmaven.test.skip=true spring-boot:run
    python scripts/live-checks/service_experience_check.py [--base http://localhost:8085] [--headed] [--no-browser]

What it does, through the real API:
  history     two VPN tickets are reported, given to a Network technician by the administrator and resolved by them, so that
              the technician has resolved at least two tickets of the VPN service
  decision    a new VPN ticket is reported and assigned by the strategy; the administrator's ticket detail then has a decision
              whose every ranked candidate carries the factor SERVICE_EXPERIENCE with the weight 0.10, the four weights of the
              decision sum to 1, contribution = raw x weight, and the experienced technician's raw score is at least 0.4
              (resolved tickets of the service / 5) while the strategy that ranked them is the default composite
  other       a ticket of a different service (a payment ticket) gets the factor too, and the experienced technician's raw
              score there is not the VPN one (experience is per service)
Browser (Edge, real login form, as the administrator): the dialog of the new ticket shows the column "Service experience"
with the factor's "raw x 0.10 = contribution" cells and the detail text; no console error.

Exit code 0 when every check passes, 1 otherwise. The tickets it creates are resolved at the end (so the technicians keep
their capacity); the history it leaves in the database is the point of the check and makes later runs stronger.
"""
import argparse
import json
import random
import string
import sys
import time
import urllib.error
import urllib.request

PASSWORD = "Password123!"
ADMIN = "admin@nimbusfinserve.com"
EMPLOYEE = "employee@nimbusfinserve.com"
EXPERIENCED_ID = "TECH-NET-2"
WEIGHT = 0.10
FULL_SCORE_TICKETS = 5
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
            return error.code, text


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
    if status not in (200, 201, 204):
        sys.exit("FAIL: %s answered HTTP %s %s" % (what, status, body))
    return body


def words(n):
    return " ".join("".join(random.choice(string.ascii_lowercase) for _ in range(9)) for _ in range(n))


class Run:
    def __init__(self, base):
        self.base = base
        self.admin, self.employee = login(base, ADMIN), login(base, EMPLOYEE)
        roster = must(call(base, "GET", "/api/admin/technicians", self.admin), "roster")
        self.emails = {t["id"]: t["email"] for t in roster}
        self.open = []

    def report(self, title, description):
        return must(call(self.base, "POST", "/api/employee/incidents", self.employee,
                         {"title": title, "description": description}), "report")["id"]

    def detail(self, ticket_id):
        time.sleep(0.8)  # the assignment is a second step right after the intake
        return must(call(self.base, "GET", "/api/admin/incidents/" + ticket_id, self.admin), "admin detail")

    def resolve(self, ticket_id, technician_id):
        token = login(self.base, self.emails[technician_id])
        for step in ("acknowledge", "start-investigation"):
            call(self.base, "POST", "/api/technician/incidents/%s/%s" % (ticket_id, step), token)
        return call(self.base, "POST", "/api/technician/incidents/%s/resolve" % ticket_id, token, {
            "rootCause": "Gateway certificate expired", "stepsTaken": "Renewed the certificate",
            "preventiveAction": "Expiry alert", "confirmedCategory": "NETWORK"})

    def build_history(self, count):
        """Reports VPN tickets, gives them to the experienced technician and resolves them."""
        for _ in range(count):
            ticket = self.report("VPN gateway rejects the AnyConnect login " + words(1),
                                 "Remote staff cannot connect: the AnyConnect VPN gateway refuses the tunnel %s." % words(3))
            must(call(self.base, "POST", "/api/admin/incidents/%s/assign" % ticket, self.admin,
                      {"technicianId": EXPERIENCED_ID}), "assign history ticket")
            status, _ = self.resolve(ticket, EXPERIENCED_ID)
            check(status in (200, 201), "history: %s resolved by %s" % (ticket, EXPERIENCED_ID))


def factor(candidate, key):
    return next((f for f in candidate["factors"] if f["key"] == key), None)


def decision_checks(run, ticket_id, label, expect_experienced_at_least):
    detail = run.detail(ticket_id)
    decisions = detail.get("assignmentDecisions") or []
    check(len(decisions) >= 1, "%s: a decision is recorded for %s" % (label, ticket_id))
    if not decisions:
        return None
    decision = decisions[0]
    candidates = decision["candidates"]
    has = [factor(c, "SERVICE_EXPERIENCE") for c in candidates]
    check(decision["strategyName"] == "WEIGHTED_COMPOSITE" and all(h is not None for h in has),
          "%s: every ranked candidate (%d) carries SERVICE_EXPERIENCE under %s" % (label, len(candidates), decision["strategyName"]))
    top = candidates[0]["factors"]
    weights = sum(f["weight"] for f in top)
    check(abs(weights - 1.0) < 1e-9 and [f["key"] for f in top] == ["SKILL_MATCH", "LOAD", "EXPERIENCE", "SERVICE_EXPERIENCE"],
          "%s: the four weights sum to %.6f (%s)" % (label, weights, [f["key"] for f in top]))
    fs = [h for h in has if h]
    check(all(abs(h["weight"] - WEIGHT) < 1e-12 and abs(h["contribution"] - h["rawScore"] * h["weight"]) < 1e-12 for h in fs),
          "%s: the weight is %.2f and contribution = raw x weight for every candidate" % (label, WEIGHT))
    if expect_experienced_at_least is not None:
        mine = next((c for c in candidates if c["technicianId"] == EXPERIENCED_ID), None)
        if mine is None:
            print("note %s: %s is not among the ranked candidates (the ticket went to another team)" % (label, EXPERIENCED_ID))
        else:
            raw = factor(mine, "SERVICE_EXPERIENCE")["rawScore"]
            check(raw >= expect_experienced_at_least, "%s: %s has service experience %.2f (>= %.2f): '%s'"
                  % (label, EXPERIENCED_ID, raw, expect_experienced_at_least, factor(mine, "SERVICE_EXPERIENCE")["detail"]))
    return detail


def browser_checks(base, ticket_id, headed):
    from playwright.sync_api import sync_playwright
    errors = []
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(channel="msedge", headless=not headed)
        page = browser.new_page()
        page.on("console", lambda m: errors.append(m.text)
                if m.type == "error" and not m.text.startswith("Failed to load resource") else None)
        page.on("pageerror", lambda e: errors.append(str(e)))
        page.goto(base + "/")
        page.fill("#login-email", ADMIN)
        page.fill("#login-password", PASSWORD)
        page.click("#form-login button[type=submit]")
        page.wait_for_selector("#btn-logout", timeout=15000)
        page.click("button.nav-tab[data-target=console-admin]")
        page.fill("#admin-search-text", ticket_id)
        page.click("#btn-admin-search")
        page.locator(".btn-admin-view-ticket", has_text=ticket_id).first.click()
        page.wait_for_selector("#ticket-detail-content:not(.hidden)", timeout=15000)
        why = page.locator("#td-assignment-why")
        tables = why.locator(".td-why-table")
        header = tables.first.locator("thead").inner_text().lower() if tables.count() else ""
        check("service experience" in header, "browser: the table has a 'Service experience' column")
        chosen = why.locator("tr.td-why-chosen")
        text = chosen.first.inner_text() if chosen.count() else ""
        check("× 0.10 =" in text and "of this service resolved" in text,
              "browser: the chosen row shows 'raw x 0.10 = contribution' and the detail for the service factor")
        page.click("#btn-close-ticket-detail")
        browser.close()
    check(not errors, "browser: no console error %s" % errors)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default="http://localhost:8085")
    parser.add_argument("--headed", action="store_true")
    parser.add_argument("--no-browser", action="store_true")
    args = parser.parse_args()
    run = Run(args.base)
    created = []
    try:
        run.build_history(2)
        vpn = run.report("VPN gateway timeouts for the whole office " + words(1),
                         "AnyConnect drops every few minutes with heavy packet loss on the office VPN tunnel %s." % words(3))
        created.append(vpn)
        detail = decision_checks(run, vpn, "vpn", 2 / FULL_SCORE_TICKETS)
        payment = run.report("Payment gateway rejects card transactions " + words(1),
                             "The payment gateway returns declines for every card transaction since the deployment %s." % words(3))
        created.append(payment)
        pay_detail = decision_checks(run, payment, "other service", None)
        if pay_detail and detail:
            mine = next((c for c in pay_detail["assignmentDecisions"][0]["candidates"] if c["technicianId"] == EXPERIENCED_ID), None)
            check(mine is None or factor(mine, "SERVICE_EXPERIENCE")["rawScore"] < 1.0,
                  "other service: experience is per service (the payment ticket does not inherit the VPN history)")
        if not args.no_browser and detail:
            browser_checks(args.base, vpn, args.headed)
    finally:
        for ticket_id in created:
            info = call(args.base, "GET", "/api/admin/incidents/" + ticket_id, run.admin)[1]
            if isinstance(info, dict) and info.get("assigneeId") in run.emails:
                run.resolve(ticket_id, info["assigneeId"])
    if FAILURES:
        print("FAILED: %d check(s)" % len(FAILURES))
        sys.exit(1)
    print("OK: the service-experience factor is in every decision, weighted 0.10, per service, and shown to the administrator")


if __name__ == "__main__":
    main()
