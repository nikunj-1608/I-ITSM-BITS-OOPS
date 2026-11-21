#!/usr/bin/env python3
"""Live check for ADR-030 and ADR-031 (SLA response breach, one transaction per ticket in the scan).

Usage: start the application with a short scan interval so the background scan runs while the script waits, e.g.

    mvn -o -Dmaven.test.skip=true spring-boot:run -Dspring-boot.run.arguments=--iitsm.sla.scan-interval-ms=3000
    python scripts/live-checks/sla_response_breach.py [--base http://localhost:8085]

Scenario (P1: 5 minute response, 30 minute resolution; the simulation clock is moved, nothing is really waited):

  A. a ticket nobody acknowledges is breached by the scan (SlaViolated RESPONSE_TARGET, once); the technician then
     acknowledges it late: it must stay BREACHED and nothing more is announced.
  B. a ticket acknowledged 7 minutes in (before or after the scan reaches it) is BREACHED with exactly one violation,
     and a timely resolution does not turn it into MET.
  C. a ticket acknowledged and resolved in time is MET with no violation.

The simulation clock is reset at the end. Exit code 0 when every check passes, 1 otherwise. Standard library only.
"""
import argparse
import json
import sys
import time
import urllib.error
import urllib.request

PASSWORD = "Password123!"
ADMIN = "admin@nimbusfinserve.com"
EMPLOYEE = "employee@nimbusfinserve.com"
STAFF = ["tech.network", "tech.net2", "tech.net3", "lead.network", "tech.db1", "tech.db2", "tech.db3", "lead.db",
         "tech.cloud1", "tech.cloud2", "tech.cloud3", "lead.cloud", "lead.desk", "tech.desk1", "tech.desk2", "tech.desk3"]
SCAN_WAIT_SECONDS = 8
FAILURES = []


def call(base, method, path, token=None, body=None):
    data = None if body is None else json.dumps(body).encode("utf-8")
    request = urllib.request.Request(base + path, data=data, method=method)
    request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            text = response.read().decode("utf-8")
            return response.status, (json.loads(text) if text else None)
    except urllib.error.HTTPError as error:
        text = error.read().decode("utf-8")
        try:
            return error.code, json.loads(text)
        except ValueError:
            return error.code, None


def login(base, email):
    status, body = call(base, "POST", "/api/auth/login", None, {"email": email, "password": PASSWORD})
    if status != 200 or not body or "token" not in body:
        sys.exit("FAIL: cannot log in as %s (HTTP %s)" % (email, status))
    return body["token"]


def check(name, condition, detail=""):
    print("%-4s %s %s" % ("ok" if condition else "FAIL", name, detail))
    if not condition:
        FAILURES.append(name)


def advance(base, admin, minutes):
    status, _ = call(base, "POST", "/api/simulation/advance?minutes=%d" % minutes, admin)
    if status != 200:
        sys.exit("FAIL: cannot advance the simulation clock (HTTP %s)" % status)


def admin_view(base, admin, ticket_id):
    status, body = call(base, "GET", "/api/admin/incidents/" + ticket_id, admin)
    if status != 200:
        sys.exit("FAIL: cannot read ticket %s (HTTP %s)" % (ticket_id, status))
    return body


def violations(view, target):
    return [r for r in view["auditTrail"]
            if r["action"] == "SlaViolated" and target in (r.get("description") or "")]


def new_p1_ticket(base, employee, admin, title, description):
    status, ticket = call(base, "POST", "/api/employee/incidents", employee, {"title": title, "description": description})
    if status not in (200, 201):
        sys.exit("FAIL: cannot create a ticket (HTTP %s)" % status)
    status, _ = call(base, "POST", "/api/admin/incidents/%s/override-priority" % ticket["id"], admin,
                     {"priority": "P1", "reason": "Live check: whole office offline"})
    if status != 200:
        sys.exit("FAIL: cannot override the priority (HTTP %s)" % status)
    return ticket["id"]


def technician_token(base, ticket_id):
    for name in STAFF:
        token = login(base, name + "@nimbusfinserve.com")
        status, _ = call(base, "GET", "/api/technician/incidents/" + ticket_id, token)
        if status == 200:
            return token
    sys.exit("FAIL: no technician can see ticket %s (not assigned?)" % ticket_id)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default="http://localhost:8085")
    base = parser.parse_args().base

    admin = login(base, ADMIN)
    employee = login(base, EMPLOYEE)
    call(base, "POST", "/api/simulation/reset", admin)
    try:
        run_scenarios(base, admin, employee)
    finally:
        call(base, "POST", "/api/simulation/reset", admin)

    if FAILURES:
        print("FAILED: " + "; ".join(FAILURES))
        sys.exit(1)
    print("OK: all SLA response-breach checks passed")


def run_scenarios(base, admin, employee):
    # A: the scan breaches an unacknowledged ticket; a late acknowledge keeps it BREACHED
    a = new_p1_ticket(base, employee, admin, "VPN gateway timeouts for the whole office",
                      "AnyConnect drops every few minutes with heavy packet loss on the office gateway tunnel.")
    tech_a = technician_token(base, a)
    advance(base, admin, 10)
    time.sleep(SCAN_WAIT_SECONDS)
    view = admin_view(base, admin, a)
    check("A: scan marks the unacknowledged ticket BREACHED", view["slaTracker"]["status"] == "BREACHED")
    check("A: one SlaViolated RESPONSE_TARGET announced", len(violations(view, "RESPONSE_TARGET")) == 1,
          "(%d)" % len(violations(view, "RESPONSE_TARGET")))
    status, _ = call(base, "POST", "/api/technician/incidents/%s/acknowledge" % a, tech_a)
    check("A: the late acknowledge is accepted", status == 200, "(HTTP %s)" % status)
    time.sleep(SCAN_WAIT_SECONDS)
    view = admin_view(base, admin, a)
    check("A: after the late acknowledge the ticket stays BREACHED", view["slaTracker"]["status"] == "BREACHED")
    check("A: no second violation", len(violations(view, "")) == 1,
          "(%d)" % len(violations(view, "")))

    # B: acknowledged 7 minutes in, then resolved in time: still BREACHED
    call(base, "POST", "/api/simulation/reset", admin)
    b = new_p1_ticket(base, employee, admin, "Kubernetes pod crash looping in the production cluster",
                      "The kubernetes pod restarts every minute with OOMKilled and the container never becomes ready on the cluster.")
    tech_b = technician_token(base, b)
    advance(base, admin, 7)
    call(base, "POST", "/api/technician/incidents/%s/acknowledge" % b, tech_b)
    call(base, "POST", "/api/technician/incidents/%s/start-investigation" % b, tech_b)
    status, _ = call(base, "POST", "/api/technician/incidents/%s/resolve" % b, tech_b,
                     {"rootCause": "Memory limit too low", "stepsTaken": "Raised the limit and redeployed",
                      "preventiveAction": "Alert on restarts", "confirmedCategory": "CLOUD"})
    check("B: the technician can resolve", status == 200, "(HTTP %s)" % status)
    time.sleep(SCAN_WAIT_SECONDS)
    view = admin_view(base, admin, b)
    check("B: a late response with a timely resolution is BREACHED, not MET", view["slaTracker"]["status"] == "BREACHED",
          "(%s)" % view["slaTracker"]["status"])
    check("B: exactly one violation, labelled RESPONSE_TARGET", len(violations(view, "")) == 1
          and len(violations(view, "RESPONSE_TARGET")) == 1, "(%d)" % len(violations(view, "")))

    # C: everything in time is MET and silent
    call(base, "POST", "/api/simulation/reset", admin)
    c = new_p1_ticket(base, employee, admin, "Payroll database connection pool exhausted",
                      "The payroll database refuses new connections and queries time out, the application reports pool exhausted.")
    tech_c = technician_token(base, c)
    advance(base, admin, 2)
    call(base, "POST", "/api/technician/incidents/%s/acknowledge" % c, tech_c)
    call(base, "POST", "/api/technician/incidents/%s/start-investigation" % c, tech_c)
    advance(base, admin, 5)
    call(base, "POST", "/api/technician/incidents/%s/resolve" % c, tech_c,
         {"rootCause": "Pool too small", "stepsTaken": "Raised the pool size", "preventiveAction": "Monitor the pool",
          "confirmedCategory": "DATABASE"})
    time.sleep(SCAN_WAIT_SECONDS)
    view = admin_view(base, admin, c)
    check("C: in-time response and resolution are MET", view["slaTracker"]["status"] == "MET",
          "(%s)" % view["slaTracker"]["status"])
    check("C: no violation announced", len(violations(view, "")) == 0)


if __name__ == "__main__":
    main()
