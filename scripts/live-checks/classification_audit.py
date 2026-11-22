#!/usr/bin/env python3
"""Live check for ADR-034 (classification evidence rules): the audit examples of stress-audit finding H9, end to end.

Usage (application running and seeded, e.g. `mvn -o -Dmaven.test.skip=true spring-boot:run`):

    python scripts/live-checks/classification_audit.py [--base http://localhost:8085]

Each example is reported through the employee API and then read back as the administrator:
  - an example that must go to triage: the ticket is flagged for triage, nobody is assigned, it is in the triage queue;
  - an example with a category: the ticket has that category (A01 may instead be in triage) and, unless it is in triage,
    it was assigned to a technician.
Exit code 0 when every example behaves as expected, 1 otherwise. Standard library only.
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

# id, expectation ("TRIAGE" or a category, "|TRIAGE" allows triage too), title, description
EXAMPLES = [
    ("A01", "SECURITY|TRIAGE", "Phishing email opened Excel macro",
     "I opened an attachment from an unknown sender and enabled the Excel macro it asked for. Now Outlook shows odd sent items."),
    ("A02", "TRIAGE", "Desk drawer locked, lost the pin", "The drawer of my office desk is locked and I lost the PIN for the key pad."),
    ("A03", "TRIAGE", "Parking garage service door stuck", "The service door in the parking garage does not open when I badge in."),
    ("A04", "TRIAGE", "Coffee machine power switch broken", "The power switch of the coffee machine on floor 2 is broken."),
    # the API needs a description of at least 20 characters, so the one-word description of the audit file is repeated
    ("A05", "NETWORK", "vpn vpn vpn", "vpn vpn vpn vpn vpn vpn vpn"),
    ("A06", "NETWORK", "DNS lookups failing for internal hosts",
     "Internal hostnames do not resolve and nslookup times out against the corporate DNS servers."),
    ("A08", "AUTHENTICATION", "Account locked after password reset",
     "After the password reset this morning my account is locked and the login page rejects my credentials."),
    ("A09", "DATABASE", "Database connection pool exhausted",
     "The orders service logs HikariCP timeouts; all connections in the database pool are in use."),
    ("A10", "HARDWARE", "Laptop power adapter not charging",
     "The laptop does not charge from its power adapter; the battery stays at 3 percent."),
    ("A11", "HARDWARE", "Docking station HDMI port dead", "Monitors stay black when connected to the HDMI port of the docking station."),
    ("A12", "APPLICATION", "Checkout fails when placing an order on the web portal",
     "Customers get an error page at checkout when they submit an order on the website."),
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


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default="http://localhost:8085")
    base = parser.parse_args().base
    employee, admin = login(base, EMPLOYEE), login(base, ADMIN)

    created = []
    for example_id, expected, title, description in EXAMPLES:
        status, ticket = call(base, "POST", "/api/employee/incidents", employee, {"title": title, "description": description})
        if status not in (200, 201):
            sys.exit("FAIL: cannot report %s (HTTP %s)" % (example_id, status))
        created.append((example_id, expected, title, ticket["id"]))
    time.sleep(1.0)  # the assignment of confident tickets is a second step right after the intake

    status, queue = call(base, "GET", "/api/admin/triage-queue", admin)
    in_queue = {item["incidentId"] if "incidentId" in item else item.get("id") for item in (queue or [])}
    for example_id, expected, title, ticket_id in created:
        status, detail = call(base, "GET", "/api/admin/incidents/" + ticket_id, admin)
        triaged = bool(detail.get("needsTriage")) and not detail.get("assigneeId")
        category = detail.get("category")
        wanted, or_triage = (expected.split("|")[0], expected.endswith("|TRIAGE"))
        if wanted == "TRIAGE":
            ok = triaged and ticket_id in in_queue
            outcome = "triage" if triaged else "assigned to %s as %s" % (detail.get("assigneeId"), category)
        else:
            ok = (category == wanted and bool(detail.get("assigneeId"))) or (or_triage and triaged)
            outcome = "triage" if triaged else "%s, assigned to %s" % (category, detail.get("assigneeId"))
        print("%-4s %s %-34s expected %-16s got %s" % ("ok" if ok else "FAIL", example_id, title[:34], expected, outcome))
        if not ok:
            FAILURES.append(example_id)

    if FAILURES:
        print("FAILED: " + ", ".join(FAILURES))
        sys.exit(1)
    print("OK: all %d audit examples behave as expected" % len(created))


if __name__ == "__main__":
    main()
