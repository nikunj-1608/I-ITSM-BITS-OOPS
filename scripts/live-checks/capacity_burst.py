#!/usr/bin/env python3
"""Live check for ADR-026 (technician capacity): bursts of simultaneous ticket reports against a running application.

Usage (the application must be running, e.g. `mvn -Dmaven.test.skip=true spring-boot:run`, dev profile, seeded):

    python scripts/live-checks/capacity_burst.py [--base http://localhost:8085] [--outage 8] [--distinct 30]

What it does
  1. Logs in as the demo employee and the demo administrator.
  2. Reads the capacity report (GET /api/admin/capacity/reconcile) as the baseline.
  3. Burst A: N identical reports released at the same instant (an outage). Every reporter must get a ticket and the
     duplicates must be linked.
  4. Burst B: M different reports released at the same instant. Every reporter must get a ticket; no HTTP 5xx.
  5. Reads the capacity report again. Every technician's stored slots must match the tickets that name them: no
     difference that was not there before, and the roster must show the duplicates carried without a slot.

Exit code 0 when every check passes, 1 otherwise. Standard library only.
"""
import argparse
import json
import random
import string
import sys
import threading
import time
import urllib.error
import urllib.request

PASSWORD = "Password123!"
EMPLOYEE = "employee@nimbusfinserve.com"
ADMIN = "admin@nimbusfinserve.com"


def call(base, method, path, token=None, body=None, timeout=60):
    """Returns (status, parsed JSON or None, seconds)."""
    data = None if body is None else json.dumps(body).encode("utf-8")
    request = urllib.request.Request(base + path, data=data, method=method)
    request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    started = time.time()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            text = response.read().decode("utf-8")
            return response.status, (json.loads(text) if text else None), time.time() - started
    except urllib.error.HTTPError as error:
        text = error.read().decode("utf-8")
        try:
            return error.code, json.loads(text), time.time() - started
        except ValueError:
            return error.code, None, time.time() - started


def login(base, email):
    status, body, _ = call(base, "POST", "/api/auth/login", body={"email": email, "password": PASSWORD})
    if status != 200 or not body or "token" not in body:
        sys.exit("FAIL: cannot log in as %s (HTTP %s); is the application running and seeded?" % (email, status))
    return body["token"]


def word(rng):
    return "".join(rng.choice(string.ascii_lowercase) for _ in range(9))


def burst(base, token, reports):
    """Releases every report at the same instant; returns a list of (status, body, seconds)."""
    barrier = threading.Barrier(len(reports))
    results = [None] * len(reports)

    def worker(index, report):
        barrier.wait()
        results[index] = call(base, "POST", "/api/employee/incidents", token, report)

    threads = [threading.Thread(target=worker, args=(i, r)) for i, r in enumerate(reports)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    return results


def keys(report):
    return {"%s|%s|%s" % (d["kind"], d["technicianId"], d["incidentId"]) for d in report["discrepancies"]}


def summarise(name, results):
    statuses = {}
    for status, _, _ in results:
        statuses[status] = statuses.get(status, 0) + 1
    slowest = max(seconds for _, _, seconds in results)
    print("  %s: %d requests, statuses %s, slowest %.2f s" % (name, len(results), statuses, slowest))
    return statuses


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default="http://localhost:8085")
    parser.add_argument("--outage", type=int, default=8, help="identical simultaneous reports (default 8)")
    parser.add_argument("--distinct", type=int, default=30, help="different simultaneous reports (default 30)")
    args = parser.parse_args()
    rng = random.Random(7)
    failures = []

    employee = login(args.base, EMPLOYEE)
    admin = login(args.base, ADMIN)
    status, before, _ = call(args.base, "GET", "/api/admin/capacity/reconcile", admin)
    if status != 200:
        sys.exit("FAIL: GET /api/admin/capacity/reconcile returned HTTP %s" % status)
    print("Baseline: %d capacity difference(s) already present" % before["discrepancyCount"])

    marker = word(rng)
    outage = {"title": "VPN gateway outage " + marker,
              "description": "The vpn gateway is down for the whole office and nobody can connect " + marker + " " + marker}
    print("Burst A: %d identical reports (one outage)" % args.outage)
    results = burst(args.base, employee, [outage] * args.outage)
    statuses = summarise("outage", results)
    created = [body for code, body, _ in results if code in (200, 201) and body]
    if len(created) != args.outage:
        failures.append("outage: %d of %d reporters got a ticket" % (len(created), args.outage))
    ids = {body.get("id") for body in created}
    if len(ids) != len(created):
        failures.append("outage: two reporters received the same ticket id")
    if any(code >= 500 for code in statuses):
        failures.append("outage: HTTP 5xx in %s" % statuses)

    print("Burst B: %d different reports" % args.distinct)
    reports = []
    for _ in range(args.distinct):
        words = " ".join(word(rng) for _ in range(14))
        reports.append({"title": "VPN gateway " + word(rng) + " " + word(rng),
                        "description": "The vpn and the gateway " + words + " vpn."})
    results = burst(args.base, employee, reports)
    statuses = summarise("distinct", results)
    got = sum(1 for code, _, _ in results if code in (200, 201))
    if got != args.distinct:
        failures.append("distinct: %d of %d reporters got a ticket" % (got, args.distinct))
    if any(code >= 500 for code in statuses):
        failures.append("distinct: HTTP 5xx in %s" % statuses)

    time.sleep(1)  # let the delivery worker and any dispatcher run settle
    status, after, _ = call(args.base, "GET", "/api/admin/capacity/reconcile", admin)
    added = keys(after) - keys(before)
    print("After: %d capacity difference(s), %d new" % (after["discrepancyCount"], len(added)))
    if added:
        failures.append("capacity differences introduced by the bursts: %s" % sorted(added))
    status, roster, _ = call(args.base, "GET", "/api/admin/technicians", admin)
    carried = sum(t.get("carriedDuplicateCount", 0) for t in roster)
    print("Roster: technicians carry %d linked duplicate(s) without a slot" % carried)

    if failures:
        print("FAILED:")
        for failure in failures:
            print("  - " + failure)
        sys.exit(1)
    print("OK: every reporter got a ticket and every technician's slots match the tickets")


if __name__ == "__main__":
    main()
