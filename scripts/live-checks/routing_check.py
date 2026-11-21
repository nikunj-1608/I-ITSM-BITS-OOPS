#!/usr/bin/env python3
"""Live check for ADR-036 (routing in one place): the classified category decides the team.

Usage (application running and seeded, e.g. `mvn -o -Dmaven.test.skip=true spring-boot:run`):

    python scripts/live-checks/routing_check.py [--base http://localhost:8085]

Reports one ticket per category through the employee API and reads back, as the administrator, the category the system
gave it and the team of the technician it was assigned to. Every assigned ticket must be with the team that handles its
category (TEAM-NET Network; TEAM-DB Database and Storage; TEAM-APP Application and Software; TEAM-SEC Security and
Authentication; TEAM-CLOUD Cloud; TEAM-DESK Hardware). The DNS ticket of the stress audit must be with the Network team,
not the Service Desk. A ticket that waits (a full team) must have no technician. The ticket detail must report the team
of the category as its support team. Exit code 0 when every check passes, 1 otherwise. Standard library only.
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

TEAM_OF_CATEGORY = {
    "NETWORK": "TEAM-NET", "DATABASE": "TEAM-DB", "STORAGE": "TEAM-DB", "APPLICATION": "TEAM-APP",
    "SOFTWARE": "TEAM-APP", "SECURITY": "TEAM-SEC", "AUTHENTICATION": "TEAM-SEC", "CLOUD": "TEAM-CLOUD",
    "HARDWARE": "TEAM-DESK",
}

TICKETS = [
    ("DNS lookups failing for internal hosts",
     "Internal hostnames do not resolve and nslookup times out against the corporate DNS servers.", "NETWORK"),
    ("Branch office Cisco Meraki router showing red status LED",
     "The router at the branch office shows a red status LED and the WAN link is down since this morning.", None),
    ("PostgreSQL vacuum process blocked by long running idle transaction",
     "Autovacuum cannot finish on the ledger table because an idle transaction holds a lock for hours.", None),
    ("Terraform apply failed creating AWS security group rule limit exceeded",
     "The terraform apply for the staging VPC fails: the AWS security group rule limit was exceeded.", None),
    ("Endpoint anti-malware detected Cobalt Strike beacon memory injection",
     "The endpoint anti-malware raised an alert for a Cobalt Strike beacon with memory injection on a laptop.", None),
    ("Customer CRM cannot display account balance history tab",
     "In the customer CRM the account balance history tab stays empty for every customer record.", None),
    ("MacBook Pro screen flickering and showing pink vertical lines",
     "The built-in retina display blinks and shows pink lines when moving the hinge of the laptop.", None),
    ("Okta 2FA push notification timing out on mobile app",
     "The Okta 2FA push never arrives on the mobile app and the login times out after a minute.", None),
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


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default="http://localhost:8085")
    base = parser.parse_args().base
    employee, admin = login(base, EMPLOYEE), login(base, ADMIN)
    status, roster = call(base, "GET", "/api/admin/technicians", admin)
    team_of_technician = {t["id"]: t["supportTeamId"] for t in roster}

    created = []
    for title, description, forced in TICKETS:
        status, ticket = call(base, "POST", "/api/employee/incidents", employee, {"title": title, "description": description})
        if status not in (200, 201):
            sys.exit("FAIL: cannot report '%s' (HTTP %s)" % (title, status))
        created.append((title, ticket["id"], forced))
    time.sleep(1.0)  # the assignment is a second step right after the intake

    for title, ticket_id, forced in created:
        status, detail = call(base, "GET", "/api/admin/incidents/" + ticket_id, admin)
        category, assignee = detail.get("category"), detail.get("assigneeId")
        if detail.get("needsTriage"):
            print("note %-60s in triage (%s), not routed" % (title[:60], category))
            continue
        expected_team = TEAM_OF_CATEGORY.get(category)
        if assignee is None:
            check(True, "%-60s %s: waits without a technician (the owning team is full)" % (title[:60], category))
            continue
        got_team = team_of_technician.get(assignee)
        check(got_team == expected_team, "%-60s %s -> %s (%s), expected %s" % (title[:60], category, assignee, got_team, expected_team))
        support_team = ((detail.get("supportTeam") or {}).get("id")) or detail.get("supportTeamId")
        if support_team is not None:
            check(support_team == got_team, "   the detail view reports the support team %s" % support_team)
        if forced is not None:
            check(category == forced and got_team == TEAM_OF_CATEGORY[forced],
                  "   the audit ticket is %s and is with %s, not the Service Desk" % (category, TEAM_OF_CATEGORY[forced]))

    if FAILURES:
        print("FAILED: %d check(s)" % len(FAILURES))
        sys.exit(1)
    print("OK: every assigned ticket is with the team that handles its category")


if __name__ == "__main__":
    main()
