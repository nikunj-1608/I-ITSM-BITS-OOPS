#!/usr/bin/env python3
"""Live check for ADR-040 (suggested resolutions, Phase K1).

Usage (application running and seeded, e.g. `mvn -o -Dmaven.test.skip=true spring-boot:run`):

    python scripts/live-checks/suggestions_check.py [--base http://localhost:8085]

What it does
  1. The employee reports a VPN outage (ticket A); the administrator gives it to TECH-NET-1, who acknowledges, starts
     and resolves it with a root cause.
  2. The employee reports a similar VPN problem (ticket B), given to TECH-NET-1 as well.
  3. TECH-NET-1, the network team lead and the administrator ask for B's suggestions: A is among them (at most 3), with
     a score >= 0.20, matched terms and the root cause; no description or reporter field is returned.
  4. A ticket with no technical words gets an empty list.
  5. A technician of another team gets 403, the employee 403, no token 401, an unknown ticket 404 ERR_SERVICE_NOT_FOUND.
  6. The employee's own view of A contains nothing about suggestions.

Exit code 0 when every check passes, 1 otherwise. Standard library only. The tickets are left in the database.
"""
import argparse
import json
import sys
import urllib.error
import urllib.request
import random
import string

PASSWORD = "Password123!"
TECHNICIAN_ID = "TECH-NET-1"
ROOT_CAUSE = "Expired TLS certificate on the AnyConnect VPN gateway"
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


def must(result, what):
    status, body = result
    if status >= 300:
        sys.exit("FAIL: %s answered HTTP %s: %s" % (what, status, body))
    return body


def raise_ticket(base, employee, admin, title, description):
    ticket = must(call(base, "POST", "/api/employee/incidents", employee,
                       {"title": title, "description": description}), "report " + title)
    must(call(base, "POST", "/api/admin/incidents/%s/assign" % ticket["id"], admin,
              {"technicianId": TECHNICIAN_ID}), "assign " + ticket["id"])
    return ticket["id"]



def finish(base, technician, ticket_id):
    """Resolves an open ticket this script created, so TECH-NET-1 gets the capacity slot back for the next run."""
    for step in ("acknowledge", "start-investigation"):
        call(base, "POST", "/api/technician/incidents/%s/%s" % (ticket_id, step), technician)
    call(base, "POST", "/api/technician/incidents/%s/resolve" % ticket_id, technician, {
        "rootCause": "Closed by the live check", "stepsTaken": "None", "preventiveAction": "None",
        "confirmedCategory": "NETWORK"})


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default="http://localhost:8085")
    base = parser.parse_args().base
    admin = login(base, "admin@nimbusfinserve.com")
    employee = login(base, "employee@nimbusfinserve.com")
    technician = login(base, "tech.network@nimbusfinserve.com")
    lead = login(base, "lead.network@nimbusfinserve.com")
    other_team = login(base, "tech.desk1@nimbusfinserve.com")

    # Four words only this run uses, shared by A and B. Earlier runs leave resolved tickets with the same wording in the
    # database (the open tickets of a run are resolved at its end); unseen words weigh most in TF-IDF, so sharing four of
    # them puts this run's A above those clones, which carry other unique words and tie each other on the rest.
    run = " ".join("".join(random.choice(string.ascii_lowercase) for _ in range(8)) for _ in range(4))
    a = raise_ticket(base, employee, admin, "VPN gateway rejects every AnyConnect login at %s" % run,
                     "Remote staff at %s cannot connect: the AnyConnect VPN gateway refuses the tunnel with a certificate error." % run)
    for step in ("acknowledge", "start-investigation"):
        must(call(base, "POST", "/api/technician/incidents/%s/%s" % (a, step), technician), step)
    must(call(base, "POST", "/api/technician/incidents/%s/resolve" % a, technician, {
        "rootCause": ROOT_CAUSE,
        "stepsTaken": "Renewed the gateway certificate and restarted the VPN tunnel service",
        "preventiveAction": "Certificate expiry alert 30 days ahead",
        "confirmedCategory": "NETWORK"}), "resolve A")
    b = raise_ticket(base, employee, admin, "AnyConnect VPN tunnel fails with certificate error at %s" % run,
                     "Since this morning the VPN gateway at %s refuses my AnyConnect tunnel and shows a certificate warning." % run)
    print("ticket A (resolved) %s, ticket B (new) %s" % (a, b))

    path = "/api/technician/incidents/%s/suggested-resolutions" % b
    for name, token in (("assigned technician", technician), ("team lead", lead), ("administrator", admin)):
        status, body = call(base, "GET", path, token)
        hit = next((s for s in (body or []) if s.get("sourceId") == a), None)
        check(status == 200 and 1 <= len(body) <= 3 and hit is not None,
              "%s: HTTP %s, %s suggestion(s), A among them" % (name, status, len(body or [])))
        if name == "assigned technician" and hit:
            print("     A: score %.3f, terms %s, shown as %s" % (hit["similarity"], hit["matchedTerms"], hit["sourceDisplayId"]))
            check(hit["similarity"] >= 0.20 and hit["matchedTerms"] and hit["rootCause"] == ROOT_CAUSE,
                  "A carries score >= 0.20, matched terms and its root cause")
            check(not any(key in hit for key in ("description", "problemText", "reporterId")),
                  "no description or reporter field in a suggestion")
            check(all(s["sourceId"] != b for s in body), "B is not suggested for itself")

    c = raise_ticket(base, employee, admin, "Question about the quarterly offsite catering menu",
                     "Could someone tell me whether the quarterly offsite lunch includes vegetarian options for everyone?")
    status, body = call(base, "GET", "/api/technician/incidents/%s/suggested-resolutions" % c, admin)
    check(status == 200 and body == [], "ticket without technical words: HTTP %s, %s" % (status, body))

    status, body = call(base, "GET", path, other_team)
    check(status == 403 and (body or {}).get("code") == "ERR_UNAUTHORIZED_OPERATION",
          "technician of another team: HTTP %s %s" % (status, (body or {}).get("code")))
    status, _ = call(base, "GET", path, employee)
    check(status == 403, "employee: HTTP %s" % status)
    status, _ = call(base, "GET", path)
    check(status == 401, "no token: HTTP %s" % status)
    status, body = call(base, "GET", "/api/technician/incidents/NET-2099-999999/suggested-resolutions", admin)
    check(status == 404 and (body or {}).get("code") == "ERR_SERVICE_NOT_FOUND",
          "unknown ticket: HTTP %s %s" % (status, (body or {}).get("code")))

    status, body = call(base, "GET", "/api/employee/incidents/%s" % a, employee)
    text = json.dumps(body).lower()
    check(status == 200 and "suggest" not in text and "similarity" not in text,
          "employee view of A has nothing about suggestions")

    for ticket_id in (b, c):
        finish(base, technician, ticket_id)
    print("%d check(s) failed" % len(FAILURES) if FAILURES else "all checks passed")
    sys.exit(1 if FAILURES else 0)


if __name__ == "__main__":
    main()
