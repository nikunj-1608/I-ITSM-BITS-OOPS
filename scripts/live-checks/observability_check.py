#!/usr/bin/env python3
"""Live check for ADR-038 (notification metrics and administrator-only Actuator pages).

Usage (application running and seeded, e.g. `mvn -o -Dmaven.test.skip=true spring-boot:run`):

    python scripts/live-checks/observability_check.py [--base http://localhost:8085]

What it does
  1. As the administrator: GET /actuator/metrics/iitsm.notifications.pending and .dropped answer 200 with a numeric value.
  2. An employee reports a ticket (which queues notifications); within a few seconds the pending gauge is back to 0 and the
     dropped counter has not grown.
  3. As an employee and as a technician: GET /actuator/metrics answers 403; without a token it answers 401.
  4. GET /actuator/health needs no token (it answers 200 or 503, never 401 or 403).

Exit code 0 when every check passes, 1 otherwise. Standard library only.
"""
import argparse
import json
import sys
import time
import urllib.error
import urllib.request

PASSWORD = "Password123!"
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


def metric(base, token, name):
    status, body = call(base, "GET", "/actuator/metrics/" + name, token)
    value = None
    if status == 200 and body:
        value = body["measurements"][0]["value"]
    return status, value


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default="http://localhost:8085")
    base = parser.parse_args().base
    admin = login(base, "admin@nimbusfinserve.com")
    employee = login(base, "employee@nimbusfinserve.com")
    technician = login(base, "tech.network@nimbusfinserve.com")

    status, pending = metric(base, admin, "iitsm.notifications.pending")
    check(status == 200 and pending is not None, "administrator: pending gauge answers 200 (value %s)" % pending)
    status, dropped_before = metric(base, admin, "iitsm.notifications.dropped")
    check(status == 200 and dropped_before is not None, "administrator: dropped counter answers 200 (value %s)" % dropped_before)

    call(base, "POST", "/api/employee/incidents", employee, {
        "title": "VPN gateway timeouts for the whole office",
        "description": "AnyConnect drops every few minutes with heavy packet loss on the office gateway tunnel."})
    settled = False
    for _ in range(20):
        status, pending = metric(base, admin, "iitsm.notifications.pending")
        if status == 200 and pending == 0:
            settled = True
            break
        time.sleep(0.5)
    check(settled, "after a report the pending gauge returns to 0 (value %s)" % pending)
    status, dropped_after = metric(base, admin, "iitsm.notifications.dropped")
    check(dropped_after == dropped_before, "the dropped counter did not grow (%s -> %s)" % (dropped_before, dropped_after))

    check(call(base, "GET", "/actuator/metrics", employee)[0] == 403, "employee: /actuator/metrics is 403")
    check(call(base, "GET", "/actuator/metrics", technician)[0] == 403, "technician: /actuator/metrics is 403")
    check(call(base, "GET", "/actuator/metrics")[0] == 401, "no token: /actuator/metrics is 401")
    health = call(base, "GET", "/actuator/health")[0]
    check(health in (200, 503), "no token: /actuator/health is reachable (HTTP %s)" % health)

    if FAILURES:
        print("FAILED: %d check(s)" % len(FAILURES))
        sys.exit(1)
    print("OK: notification metrics are visible to administrators only")


if __name__ == "__main__":
    main()
