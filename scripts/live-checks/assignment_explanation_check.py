#!/usr/bin/env python3
"""Live check for ADR-044 Phase L2 ("Why this technician"): decisions on every path, admin only, shown in the dialog.

Usage (application running and seeded; for the browser part Python with Playwright and Microsoft Edge installed):

    mvn -o -Dmaven.test.skip=true spring-boot:run
    python scripts/live-checks/assignment_explanation_check.py [--base http://localhost:8085] [--headed] [--no-browser]

API (each result read back through the administrator's ticket detail):
  intake      a clear network ticket is assigned by the strategy: pool OWNING_TEAM, the chosen technician is the
              assignee and rank 1, every ranked candidate is of the Network team, factors = raw x weight, names shown
  duplicate   the same report again rides the first ticket's technician: INHERITED_FROM_PARENT naming the first ticket
  manual      a vague ticket in triage has no decision; the administrator assigns it: MANUAL by ADMIN-SYS-01
  requeue     the technician flags the first ticket, the administrator rejects the request: REQUEUED_TO_SAME_TECHNICIAN
  access      the technician gets 403 on the admin detail and no candidates in their own views; so does the employee
Browser (Edge, real login form, as the administrator):
  the dialog of the first ticket shows the section, the pool and strategy, one row per ranked candidate with the chosen
  row highlighted, factor cells "raw x weight = contribution"; the manual ticket shows its sentence; a ticket without a
  decision shows "No assignment record for this ticket"; no console error

Exit code 0 when every check passes, 1 otherwise. The tickets with a technician, and open ones an interrupted earlier
run left, are resolved, so the technicians keep their capacity and a new report never links to an old one.
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
ADMIN, ADMIN_ID = "admin@nimbusfinserve.com", "ADMIN-SYS-01"
EMPLOYEE = "employee@nimbusfinserve.com"
TITLE = "VPN gateway timeouts for office "
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


def must(status_body, what):
    status, body = status_body
    if status not in (200, 201, 204):
        sys.exit("FAIL: %s answered HTTP %s %s" % (what, status, body))
    return body


def words(n):
    return " ".join("".join(random.choice(string.ascii_lowercase) for _ in range(9)) for _ in range(n))


class Run:
    def __init__(self, base):
        self.base = base
        self.admin, self.employee = login(base, ADMIN), login(base, EMPLOYEE)
        self.roster = {t["id"]: t for t in must(call(base, "GET", "/api/admin/technicians", self.admin), "roster")}
        self.to_resolve = []

    def report(self, title, description):
        return must(call(self.base, "POST", "/api/employee/incidents", self.employee,
                         {"title": title, "description": description}), "report")["id"]

    def detail(self, ticket_id):
        time.sleep(0.8)  # the assignment is a second step right after the intake
        return must(call(self.base, "GET", "/api/admin/incidents/" + ticket_id, self.admin), "admin detail")

    def token_of(self, technician_id):
        return login(self.base, self.roster[technician_id]["email"])

    def resolve(self, ticket_id, technician_id):
        token = self.token_of(technician_id)
        for step in ("acknowledge", "start-investigation"):
            call(self.base, "POST", "/api/technician/incidents/%s/%s" % (ticket_id, step), token)
        call(self.base, "POST", "/api/technician/incidents/%s/resolve" % ticket_id, token, {
            "rootCause": "Closed by the live check", "stepsTaken": "None", "preventiveAction": "None",
            "confirmedCategory": "NETWORK"})


def check_strategy_decision(run, detail):
    decisions = detail.get("assignmentDecisions") or []
    check(len(decisions) == 1, "intake: one decision recorded (%d)" % len(decisions))
    if not decisions:
        return
    d = decisions[0]
    top = d["candidates"][0] if d["candidates"] else {}
    check(d["poolKind"] == "OWNING_TEAM" and d["strategyName"] == "WEIGHTED_COMPOSITE",
          "intake: %s by %s" % (d["poolKind"], d["strategyName"]))
    check(d["chosenTechnicianId"] == detail["assigneeId"] == top.get("technicianId") and top.get("chosen") is True,
          "intake: the chosen technician %s is the assignee and rank 1" % d["chosenTechnicianId"])
    teams = {run.roster[c["technicianId"]]["supportTeamId"] for c in d["candidates"]}
    check(teams == {"TEAM-NET"}, "intake: every ranked candidate (%d) is of the Network team %s" % (len(d["candidates"]), teams))
    factors = top.get("factors", [])
    check([f["key"] for f in factors] == ["SKILL_MATCH", "LOAD", "EXPERIENCE", "SERVICE_EXPERIENCE"]
          and all(abs(f["contribution"] - f["rawScore"] * f["weight"]) < 1e-12 for f in factors),
          "intake: factors skill/load/experience/service with contribution = raw x weight")
    check(all(c["technicianName"] for c in d["candidates"]) and d["chosenTechnicianName"],
          "intake: names shown beside the ids")


def check_access(run, ticket_id, technician_id):
    technician = run.token_of(technician_id)
    status, _ = call(run.base, "GET", "/api/admin/incidents/" + ticket_id, technician)
    check(status == 403, "access: the technician gets HTTP %s on the admin detail" % status)
    for path in ("/api/technician/incidents/" + ticket_id, "/api/technician/queue"):
        text = json.dumps(must(call(run.base, "GET", path, technician), path))
        check("candidates" not in text and "assignmentDecisions" not in text and "SKILL_MATCH" not in text,
              "access: no candidates in the technician's %s" % path)
    status, _ = call(run.base, "GET", "/api/admin/incidents/" + ticket_id, run.employee)
    text = json.dumps(must(call(run.base, "GET", "/api/employee/incidents/" + ticket_id, run.employee), "employee view"))
    check(status == 403 and "candidates" not in text and "WEIGHTED_COMPOSITE" not in text,
          "access: the employee gets HTTP %s on the admin detail and nothing in their own view" % status)


def resolve_leftovers(run):
    """Open tickets of an earlier, interrupted run would make this run's first report their duplicate."""
    status, found = call(run.base, "GET", "/api/admin/incidents?query=" + TITLE.strip().replace(" ", "%20"), run.admin)
    rows = found.get("content", found) if isinstance(found, dict) else (found or [])
    for ticket in rows:
        if ticket.get("status") not in ("RESOLVED", "VERIFIED", "CLOSED") and ticket.get("assigneeId") in run.roster:
            run.resolve(ticket["id"], ticket["assigneeId"])


def api_checks(run, marker):
    resolve_leftovers(run)
    title = TITLE + marker
    description = "AnyConnect drops every few minutes with heavy packet loss on the office gateway tunnel at " + marker + "."
    first = run.report(title, description)
    detail = run.detail(first)
    if detail.get("assigneeId") is None:
        sys.exit("FAIL: the clear ticket %s was not assigned (triage or a full team); cannot check" % first)
    holder = detail["assigneeId"]
    check_strategy_decision(run, detail)

    second = run.report(title, description)
    d2 = (run.detail(second).get("assignmentDecisions") or [{}])[0]
    check(d2.get("poolKind") == "INHERITED_FROM_PARENT" and d2.get("relatedIncidentId") == first
          and d2.get("candidates") == [], "duplicate: %s rides %s (%s)" % (second, first, d2.get("poolKind")))

    vague = run.report("Something is odd " + marker, "Not sure what is wrong, it just feels odd today " + marker + ".")
    empty = run.report("Another odd feeling " + words(1), "Something else seems odd too, hard to say what " + words(2) + ".")
    check(run.detail(vague).get("assignmentDecisions") == [], "manual: the ticket in triage has no decision yet")
    must(call(run.base, "POST", "/api/admin/incidents/%s/assign" % vague, run.admin, {"technicianId": holder}), "assign")
    d3 = run.detail(vague)["assignmentDecisions"][0]
    check(d3["poolKind"] == "MANUAL" and d3["actorId"] == ADMIN_ID and d3["strategyName"] is None,
          "manual: MANUAL by %s, no strategy" % d3["actorId"])

    must(call(run.base, "POST", "/api/technician/incidents/%s/flag-wrong-domain" % first, run.token_of(holder),
              {"reason": "Not my area"}), "flag")
    queue = must(call(run.base, "GET", "/api/admin/triage-queue", run.admin), "triage queue")
    request_id = next(e["requestId"] for e in queue if e.get("incidentId", e.get("id")) == first and e.get("requestId"))
    must(call(run.base, "POST", "/api/admin/reassignments/%s/reject" % request_id, run.admin,
              {"reason": "It is your area"}), "reject")
    d4 = run.detail(first)["assignmentDecisions"]
    check(d4[0]["poolKind"] == "REQUEUED_TO_SAME_TECHNICIAN" and d4[0]["chosenTechnicianId"] == holder and len(d4) == 2,
          "requeue: newest decision REQUEUED to %s, the intake decision kept (%d)" % (holder, len(d4)))

    check_access(run, first, holder)
    run.to_resolve += [(first, holder), (second, holder), (vague, holder)]
    return first, vague, empty


def browser_checks(base, first, vague, empty, headed):
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

        def open_ticket(ticket_id):
            page.fill("#admin-search-text", ticket_id)
            page.click("#btn-admin-search")
            page.locator(".btn-admin-view-ticket", has_text=ticket_id).first.click()
            page.wait_for_selector("#ticket-detail-content:not(.hidden)", timeout=15000)
            return page.locator("#td-assignment-why")

        why = open_ticket(first)
        rows = why.locator(".td-why-table").first.locator("tbody tr")
        chosen = why.locator(".td-why-table").first.locator("tr.td-why-chosen")
        # badges and table headers are drawn in upper case (CSS), and inner_text reports the rendered text
        text = why.inner_text().lower()
        check(why.is_visible() and "owning team" in text and "weighted_composite" in text,
              "browser: the section shows the pool and the strategy")
        check(rows.count() >= 1 and chosen.count() == 1, "browser: %d candidate rows, one highlighted" % rows.count())
        tables = why.locator(".td-why-table")
        header = tables.first.locator("thead").inner_text().lower() if tables.count() else ""
        check("skill match" in header and "free capacity" in header and "experience" in header,
              "browser: factor columns Skill match / Free capacity / Experience")
        chosen_text = chosen.inner_text() if chosen.count() == 1 else ""
        check("×" in chosen_text and "=" in chosen_text, "browser: cells read raw x weight = contribution")
        check("rejected the wrong-domain request" in text, "browser: the requeue decision shows its sentence")
        page.click("#btn-close-ticket-detail")

        why = open_ticket(vague)
        check("by hand" in why.inner_text() and why.locator(".td-why-table").count() == 0,
              "browser: the manual decision shows its sentence and no table")
        page.click("#btn-close-ticket-detail")

        why = open_ticket(empty)
        check("No assignment record for this ticket" in why.inner_text(), "browser: a ticket without a decision says so")
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
    try:
        first, vague, empty = api_checks(run, words(3))
        print("tickets: intake %s, manual %s, without decision %s" % (first, vague, empty))
        if not args.no_browser:
            browser_checks(args.base, first, vague, empty, args.headed)
    finally:
        for ticket_id, technician_id in run.to_resolve:
            run.resolve(ticket_id, technician_id)
    if FAILURES:
        print("FAILED: %d check(s)" % len(FAILURES))
        sys.exit(1)
    print("OK: every assignment path is explained, to administrators only")


if __name__ == "__main__":
    main()
