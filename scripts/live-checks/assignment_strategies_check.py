#!/usr/bin/env python3
"""Live check for ADR-044 Phase L1 (explainable ranking): the refactored strategies still assign in the running app.

Usage (application running and seeded, e.g. `mvn -o -Dmaven.test.skip=true spring-boot:run`):

    python scripts/live-checks/assignment_strategies_check.py [--base http://localhost:8085]

Phase L1 changes no API: it only rebuilds the five assignment strategies on top of an explainable ranking. This script
proves that from the outside. It lists the strategies (all five, exactly one active), then, for every strategy, makes
it the active one, reports a clear ticket of a different category and checks that the ticket was assigned to a
technician of the team that handles its category (ADR-036). A ticket in triage or waiting for a full team is reported
as a note, not a failure (classification and capacity are not under test). At the end the default strategy
WEIGHTED_COMPOSITE is active again and every ticket the script created is resolved, so the next run starts with free
capacity. Exit code 0 when every check passes, 1 otherwise. Standard library only.
"""
import argparse
import json
import sys
import time
import urllib.error
import urllib.request

PASSWORD = "Password123!"
EMPLOYEE = "employee@nimbusfinserve.com"
ADMIN = "admin@nimbusfinserve.com"
DEFAULT_STRATEGY = "WEIGHTED_COMPOSITE"
STRATEGIES = ["WEIGHTED_COMPOSITE", "SKILL_BASED", "LOAD_BASED", "EXPERIENCE_BASED", "ROUND_ROBIN"]
TEAM_OF_CATEGORY = {
    "NETWORK": "TEAM-NET", "DATABASE": "TEAM-DB", "STORAGE": "TEAM-DB", "APPLICATION": "TEAM-APP",
    "SOFTWARE": "TEAM-APP", "SECURITY": "TEAM-SEC", "AUTHENTICATION": "TEAM-SEC", "CLOUD": "TEAM-CLOUD",
    "HARDWARE": "TEAM-DESK",
}
TICKETS = [
    ("DNS lookups failing for internal hosts",
     "Internal hostnames do not resolve and nslookup times out against the corporate DNS servers."),
    ("PostgreSQL vacuum process blocked by long running idle transaction",
     "Autovacuum cannot finish on the ledger table because an idle transaction holds a lock for hours."),
    ("Terraform apply failed creating AWS security group rule limit exceeded",
     "The terraform apply for the staging VPC fails: the AWS security group rule limit was exceeded."),
    ("Endpoint anti-malware detected Cobalt Strike beacon memory injection",
     "The endpoint anti-malware raised an alert for a Cobalt Strike beacon with memory injection on a laptop."),
    ("Customer CRM cannot display account balance history tab",
     "In the customer CRM the account balance history tab stays empty for every customer record."),
]
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


def set_active(base, admin, name):
    status, body = call(base, "PUT", "/api/admin/assignment/strategies/active", admin, {"strategyName": name})
    return status == 200 and (body or {}).get("name") == name and (body or {}).get("active") is True


def resolve(base, email, ticket_id, category):
    """Gives the technician the slot back so the next run starts with free capacity."""
    technician = login(base, email)
    for step in ("acknowledge", "start-investigation"):
        call(base, "POST", "/api/technician/incidents/%s/%s" % (ticket_id, step), technician)
    call(base, "POST", "/api/technician/incidents/%s/resolve" % ticket_id, technician, {
        "rootCause": "Closed by the live check", "stepsTaken": "None", "preventiveAction": "None",
        "confirmedCategory": category})


def check_listing(base, admin):
    status, listing = call(base, "GET", "/api/admin/assignment/strategies", admin)
    names = sorted(s.get("name") for s in (listing or []))
    check(status == 200 and names == sorted(STRATEGIES), "the five strategies are listed: %s" % names)
    check(sum(1 for s in (listing or []) if s.get("active")) == 1, "exactly one strategy is active")


def try_strategy(base, employee, admin, roster, strategy, ticket, run_id, to_resolve):
    check(set_active(base, admin, strategy), "%s is now the active strategy" % strategy)
    title, description = ticket
    status, created = call(base, "POST", "/api/employee/incidents", employee,
                           {"title": title, "description": description + " Reference " + run_id + "."})
    if status not in (200, 201):
        check(False, "%s: report '%s' (HTTP %s)" % (strategy, title, status))
        return
    time.sleep(1.0)  # the assignment is a second step right after the intake
    status, detail = call(base, "GET", "/api/admin/incidents/" + created["id"], admin)
    category, assignee = detail.get("category"), detail.get("assigneeId")
    if detail.get("needsTriage") or assignee is None:
        print("note %-18s %s (%s) in triage or waiting; nothing to check" % (strategy, created["id"], category))
        return
    team = roster.get(assignee, {}).get("supportTeamId")
    check(team == TEAM_OF_CATEGORY.get(category),
          "%-18s %s %s -> %s (%s)" % (strategy, created["id"], category, assignee, team))
    to_resolve.append((roster[assignee]["email"], created["id"], category))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default="http://localhost:8085")
    base = parser.parse_args().base
    employee, admin = login(base, EMPLOYEE), login(base, ADMIN)
    _, technicians = call(base, "GET", "/api/admin/technicians", admin)
    roster = {t["id"]: t for t in technicians}
    run_id = "LCHK%d" % int(time.time())
    check_listing(base, admin)
    to_resolve = []
    try:
        for strategy, ticket in zip(STRATEGIES, TICKETS):
            try_strategy(base, employee, admin, roster, strategy, ticket, run_id, to_resolve)
    finally:
        check(set_active(base, admin, DEFAULT_STRATEGY), "the default strategy %s is active again" % DEFAULT_STRATEGY)
        for email, ticket_id, category in to_resolve:
            resolve(base, email, ticket_id, category)
    if FAILURES:
        print("FAILED: %d check(s)" % len(FAILURES))
        sys.exit(1)
    print("OK: every strategy still assigns its ticket to the team of the ticket's category")


if __name__ == "__main__":
    main()
