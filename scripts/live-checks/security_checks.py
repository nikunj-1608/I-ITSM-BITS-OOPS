#!/usr/bin/env python3
"""Live check for ADR-032 and ADR-033 (team queue, notification ownership, login burst, lockout, exposure).

Usage: start the application with the settings prod and demo use for exposure, and the default connection pool:

    mvn -o -Dmaven.test.skip=true spring-boot:run "-Dspring-boot.run.arguments=--iitsm.security.allowed-email-domains=nimbusfinserve.com --springdoc.api-docs.enabled=false --springdoc.swagger-ui.enabled=false"
    python scripts/live-checks/security_checks.py [--base http://localhost:8085]

Checks (the simulation clock is moved for the lock and always reset):
  A. 30 simultaneous logins, half with a wrong password, all answered 200 or 401 and none slower than 5 seconds.
  B. five wrong passwords lock an account; the lock holds after 14 minutes and is gone after 16 (login and token).
  C. an employee's notification cannot be marked read by someone else (404, unchanged); the owner can (204).
  D. a colleague's ticket is not in a technician's team queue; the team lead and the assignee see it.
  E. registration with an external domain or an unknown department is a 400; the API documentation is not served; the
     retired alias tech.database@ does not sign in.

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
import uuid

PASSWORD = "Password123!"
ADMIN = "admin@nimbusfinserve.com"
EMPLOYEE = "employee@nimbusfinserve.com"
LEAD = "lead.network@nimbusfinserve.com"
BURST = 30
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


def check(ok, text):
    print("%-4s %s" % ("ok" if ok else "FAIL", text))
    if not ok:
        FAILURES.append(text)


def login(base, email, password=PASSWORD):
    return call(base, "POST", "/api/auth/login", None, {"email": email, "password": password})


def token_of(base, email):
    status, body = login(base, email)
    if status != 200:
        sys.exit("FAIL: cannot log in as %s (HTTP %s)" % (email, status))
    return body["token"]


def register(base, email, department="Engineering"):
    return call(base, "POST", "/api/auth/register", None,
                {"name": "Live Check", "email": email, "password": PASSWORD, "department": department})


def new_email(prefix):
    return "%s.%s@nimbusfinserve.com" % (prefix, uuid.uuid4().hex[:8])


def words(rng, n):
    return " ".join("".join(rng.choice(string.ascii_lowercase) for _ in range(9)) for _ in range(n))


def check_burst(base):
    emails = []
    for _ in range(BURST):
        email = new_email("burst")
        status, _ = register(base, email)
        if status != 200:
            sys.exit("FAIL: cannot register %s (HTTP %s)" % (email, status))
        emails.append(email)
    results = [None] * BURST
    barrier = threading.Barrier(BURST)

    def worker(i):
        barrier.wait()
        started = time.time()
        status, _ = login(base, emails[i], PASSWORD if i % 2 == 0 else "Wrong-" + PASSWORD)
        results[i] = (status, time.time() - started)

    threads = [threading.Thread(target=worker, args=(i,)) for i in range(BURST)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    statuses = [r[0] for r in results]
    check(set(statuses) <= {200, 401}, "A: every login answered 200 or 401, none 500 (%s)" % sorted(set(statuses)))
    check(statuses.count(200) == BURST // 2, "A: exactly the good passwords signed in (%d)" % statuses.count(200))
    slowest = max(r[1] for r in results)
    check(slowest < 5, "A: the slowest login took %.2f s" % slowest)


def check_lockout(base, admin):
    email = new_email("lock")
    register(base, email)
    token = token_of(base, email)
    for _ in range(5):
        login(base, email, "Wrong-" + PASSWORD)
    status, body = login(base, email)
    check(status == 401 and body.get("message") == "Invalid credentials", "B: a locked account answers the generic 401")
    check(call(base, "GET", "/api/auth/me", token)[0] == 401, "B: the token of a locked account is refused")
    call(base, "POST", "/api/simulation/advance?minutes=14", admin)
    check(login(base, email)[0] == 401, "B: still locked after 14 minutes")
    call(base, "POST", "/api/simulation/advance?minutes=2", admin)
    check(login(base, email)[0] == 200, "B: signs in again after 16 minutes")
    check(call(base, "GET", "/api/auth/me", token)[0] == 200, "B: the old token works again after the lock expired")


def check_notifications(base):
    employee = token_of(base, EMPLOYEE)
    rng = random.Random()
    call(base, "POST", "/api/employee/incidents", employee,
         {"title": "VPN gateway " + words(rng, 2), "description": "The vpn and the gateway " + words(rng, 12) + " vpn."})
    time.sleep(1.5)  # notifications are delivered a moment after the response
    status, notifications = call(base, "GET", "/api/notifications", employee)
    if status != 200 or not notifications:
        sys.exit("FAIL: the employee has no notification to test with (HTTP %s)" % status)
    notification_id = notifications[0]["id"]
    other = token_of(base, "ceo@nimbusfinserve.com")
    status, body = call(base, "POST", "/api/notifications/%s/read" % notification_id, other)
    check(status == 404 and body.get("code") == "ERR_SERVICE_NOT_FOUND", "C: another employee gets 404 (HTTP %s)" % status)
    status, _ = call(base, "POST", "/api/notifications/%s/read" % notification_id, token_of(base, ADMIN))
    check(status == 404, "C: the administrator is not the owner either (HTTP %s)" % status)
    status, _ = call(base, "POST", "/api/notifications/%s/read" % notification_id, employee)
    check(status == 204, "C: the owner marks it read (HTTP %s)" % status)


def check_team_queue(base, admin):
    employee = token_of(base, EMPLOYEE)
    rng = random.Random()
    status, ticket = call(base, "POST", "/api/employee/incidents", employee,
                          {"title": "VPN gateway " + words(rng, 2),
                           "description": "The vpn and the gateway " + words(rng, 14) + " vpn."})
    ticket_id = ticket["id"]
    status, detail = call(base, "GET", "/api/admin/incidents/" + ticket_id, admin)
    assignee_id = detail.get("assigneeId")
    if not assignee_id:
        sys.exit("FAIL: the ticket was not auto-assigned")
    status, roster = call(base, "GET", "/api/admin/technicians", admin)
    by_id = {t["id"]: t for t in roster}
    team = by_id[assignee_id]["supportTeamId"]
    colleagues = [t["email"] for t in roster if t["supportTeamId"] == team and t["id"] != assignee_id
                  and t["role"] == "ROLE_TECHNICIAN"]
    lead = next((t["email"] for t in roster if t["supportTeamId"] == team and t["role"] == "ROLE_TEAM_LEAD"), None)

    def ids(email):
        status, queue = call(base, "GET", "/api/technician/team-queue", token_of(base, email))
        return {t["id"] for t in queue} if status == 200 else None

    if by_id[assignee_id]["role"] == "ROLE_TECHNICIAN":
        check(ticket_id in (ids(by_id[assignee_id]["email"]) or set()), "D: the assignee sees the ticket in the team view")
    views = [ids(c) for c in colleagues]
    check(bool(colleagues) and all(v is not None and ticket_id not in v for v in views),
          "D: no colleague of the assignee sees it (%d checked, sizes %s)" % (len(colleagues), [len(v or []) for v in views]))
    if lead:
        check(ticket_id in (ids(lead) or set()), "D: the team lead sees it")
    check(ticket_id in (ids(ADMIN) or set()), "D: the administrator sees it")


def check_exposure(base):
    status, body = register(base, new_email("ext").replace("@nimbusfinserve.com", "@gmail.com"))
    check(status == 400 and body.get("code") == "ERR_VALIDATION_FAILED", "E: an external e-mail domain is refused (HTTP %s)" % status)
    status, body = register(base, new_email("dept"), "Underwater Basket Weaving")
    check(status == 400 and "Choose one of" in (body or {}).get("message", ""),
          "E: an unknown department is refused and the valid ones are listed (HTTP %s)" % status)
    status, _ = register(base, new_email("hr"), "Human Resources")
    check(status == 200, "E: a department of the registration form is accepted (HTTP %s)" % status)
    check(call(base, "GET", "/v3/api-docs")[0] == 404, "E: /v3/api-docs is not served")
    check(call(base, "GET", "/swagger-ui/index.html")[0] == 404, "E: /swagger-ui/index.html is not served")
    check(login(base, "tech.database@nimbusfinserve.com")[0] == 401, "E: the alias tech.database@ does not sign in")
    check(login(base, "tech.db1@nimbusfinserve.com")[0] == 200, "E: tech.db1@ still signs in")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default="http://localhost:8085")
    base = parser.parse_args().base
    admin = token_of(base, ADMIN)
    call(base, "POST", "/api/simulation/reset", admin)
    try:
        check_burst(base)
        check_lockout(base, admin)
        check_notifications(base)
        check_team_queue(base, admin)
        check_exposure(base)
    finally:
        call(base, "POST", "/api/simulation/reset", admin)
    if FAILURES:
        print("FAILED: %d check(s): %s" % (len(FAILURES), "; ".join(FAILURES)))
        sys.exit(1)
    print("OK: all security checks passed")


if __name__ == "__main__":
    main()
