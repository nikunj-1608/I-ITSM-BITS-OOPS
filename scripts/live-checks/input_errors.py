#!/usr/bin/env python3
"""Live check for ADR-027 (input validation): the requests the stress audit observed ending in HTTP 500.

Usage (the application must be running and seeded, e.g. `mvn -Dmaven.test.skip=true spring-boot:run`):

    python scripts/live-checks/input_errors.py [--base http://localhost:8085]

Every request below must end with the status and error code in the table, never with 500. The valid control requests
at the end must still succeed. Exit code 0 when every check passes, 1 otherwise. Standard library only.
"""
import argparse
import json
import sys
import urllib.error
import urllib.request

PASSWORD = "Password123!"
ADMIN = "admin@nimbusfinserve.com"
EMPLOYEE = "employee@nimbusfinserve.com"

# (name, method, path, body, needs admin token, expected status, expected error code or None)
# {ticket} is replaced by the id of a ticket the script creates first.
CASES = [
    ("search: unknown priority", "GET", "/api/admin/incidents?priority=P9", None, True, 400, "ERR_INVALID_PARAMETER"),
    ("search: unknown category", "GET", "/api/admin/incidents?category=PRINTER", None, True, 400, "ERR_INVALID_PARAMETER"),
    ("search: unknown status", "GET", "/api/admin/incidents?status=LIMBO", None, True, 400, "ERR_INVALID_PARAMETER"),
    ("search: unparseable date", "GET", "/api/admin/incidents?fromDate=yesterday", None, True, 400, "ERR_INVALID_PARAMETER"),
    ("designation OVERLORD", "PUT", "/api/admin/users/EMP-DEMO-01/designation", {"designation": "OVERLORD"}, True, 400, "ERR_VALIDATION_FAILED"),
    ("strategy NOPE", "PUT", "/api/admin/assignment/strategies/active", {"strategyName": "NOPE"}, True, 400, "ERR_VALIDATION_FAILED"),
    ("simulation scenario S99", "POST", "/api/simulation/run/S99", None, True, 400, "ERR_VALIDATION_FAILED"),
    ("simulation speed -5", "POST", "/api/simulation/speed?multiplier=-5", None, True, 400, "ERR_VALIDATION_FAILED"),
    ("simulation speed 0", "POST", "/api/simulation/speed?multiplier=0", None, True, 400, "ERR_VALIDATION_FAILED"),
    ("simulation speed not a number", "POST", "/api/simulation/speed?multiplier=fast", None, True, 400, "ERR_INVALID_PARAMETER"),
    ("simulation advance -30 minutes", "POST", "/api/simulation/advance?minutes=-30", None, True, 400, "ERR_VALIDATION_FAILED"),
    ("simulation advance 0 minutes", "POST", "/api/simulation/advance?minutes=0", None, True, 400, "ERR_VALIDATION_FAILED"),
    ("simulation advance far too long", "POST", "/api/simulation/advance?minutes=9223372036854775807", None, True, 400, "ERR_VALIDATION_FAILED"),
    ("accept-suggestion category PRINTERS", "POST", "/api/admin/triage/{ticket}/accept-suggestion", {"category": "PRINTERS"}, True, 400, "ERR_VALIDATION_FAILED"),
    ("accept-suggestion unknown service id", "POST", "/api/admin/triage/{ticket}/accept-suggestion",
     {"category": "NETWORK", "serviceId": "SVC-DOES-NOT-EXIST"}, True, 404, "ERR_SERVICE_NOT_FOUND"),
    ("reclassify unknown service id", "POST", "/api/admin/incidents/{ticket}/reclassify",
     {"category": "NETWORK", "serviceId": "SVC-DOES-NOT-EXIST", "reason": "x"}, True, 404, "ERR_SERVICE_NOT_FOUND"),
    ("wrong HTTP method", "GET", "/api/auth/login", None, False, 405, "ERR_METHOD_NOT_ALLOWED"),
    ("favicon (missing resource)", "GET", "/favicon.ico", None, False, 404, "ERR_NOT_FOUND"),
    # controls: valid requests must keep working
    ("control: valid search", "GET", "/api/admin/incidents?priority=P1&priority=P3&category=NETWORK", None, True, 200, None),
    ("control: valid speed", "POST", "/api/simulation/speed?multiplier=1", None, True, 200, None),
    ("control: valid advance", "POST", "/api/simulation/advance?minutes=1", None, True, 200, None),
]


def call(base, method, path, token, body):
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


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default="http://localhost:8085")
    args = parser.parse_args()

    status, body = call(args.base, "POST", "/api/auth/login", None, {"email": ADMIN, "password": PASSWORD})
    if status != 200 or not body or "token" not in body:
        sys.exit("FAIL: cannot log in as %s (HTTP %s); is the application running and seeded?" % (ADMIN, status))
    token = body["token"]

    status, body = call(args.base, "POST", "/api/auth/login", None, {"email": EMPLOYEE, "password": PASSWORD})
    if status != 200 or not body or "token" not in body:
        sys.exit("FAIL: cannot log in as %s (HTTP %s)" % (EMPLOYEE, status))
    status, ticket = call(args.base, "POST", "/api/employee/incidents", body["token"],
                          {"title": "Live check ticket for input validation",
                           "description": "Created by scripts/live-checks/input_errors.py to have a real ticket id."})
    if status not in (200, 201) or not ticket or "id" not in ticket:
        sys.exit("FAIL: cannot create a ticket (HTTP %s)" % status)

    failures = []
    for name, method, path, payload, needs_token, want_status, want_code in CASES:
        path = path.replace("{ticket}", ticket["id"])
        got_status, got_body = call(args.base, method, path, token if needs_token else None, payload)
        got_code = got_body.get("code") if isinstance(got_body, dict) else None
        ok = got_status == want_status and (want_code is None or got_code == want_code)
        print("%-4s %-38s HTTP %s %s" % ("ok" if ok else "FAIL", name, got_status, got_code or ""))
        if not ok:
            failures.append("%s: expected %s %s, got %s %s" % (name, want_status, want_code or "", got_status, got_code or ""))

    if failures:
        print("FAILED:")
        for failure in failures:
            print("  - " + failure)
        sys.exit(1)
    print("OK: %d requests behaved as expected, none ended in HTTP 500" % len(CASES))


if __name__ == "__main__":
    main()
