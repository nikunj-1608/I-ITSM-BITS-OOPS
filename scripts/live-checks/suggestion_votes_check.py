#!/usr/bin/env python3
"""Live check for ADR-041 (votes on suggested resolutions, Phase K2).

Usage (application running and seeded, e.g. `mvn -o -Dmaven.test.skip=true spring-boot:run`):

    python scripts/live-checks/suggestion_votes_check.py [--base http://localhost:8085]

What it does
  1. The employee reports a VPN outage (ticket A), TECH-NET-1 resolves it; the employee reports a similar ticket B, also
     given to TECH-NET-1. B's suggestions contain A with 0 votes and score == similarity.
  2. TECH-NET-1 votes "worked" on A for B (counts 1/0), then changes the vote to "didn't" (counts 0/1, not 1/1).
  3. The network team lead and the administrator vote "worked": counts 2/1, and B's list shows score = similarity x
     (0.8 + 0.4 x 3/5), inside the 0.8..1.2 band.
  4. Refusals: unknown source 404 ERR_NOT_FOUND, article source 404, unknown sourceType 400 ERR_MALFORMED_JSON, missing
     verdict 400 ERR_VALIDATION_FAILED, other-team technician 403, employee 403, no token 401.

Exit code 0 when every check passes, 1 otherwise. Standard library only. The tickets and votes are left in the database.
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
VOTE_WEIGHT = 0.20
FAILURES = []


def call(base, method, path, token=None, body=None, raw=None):
    data = raw.encode("utf-8") if raw is not None else (None if body is None else json.dumps(body).encode("utf-8"))
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


def suggestion(base, token, ticket, source):
    body = must(call(base, "GET", "/api/technician/incidents/%s/suggested-resolutions" % ticket, token), "suggestions")
    return next((s for s in body if s["sourceId"] == source), None)



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
    a = raise_ticket(base, employee, admin, "Branch VPN gateway refuses AnyConnect tunnels at %s" % run,
                     "The branch office VPN gateway at %s refuses every AnyConnect tunnel with a certificate error since today." % run)
    for step in ("acknowledge", "start-investigation"):
        must(call(base, "POST", "/api/technician/incidents/%s/%s" % (a, step), technician), step)
    must(call(base, "POST", "/api/technician/incidents/%s/resolve" % a, technician, {
        "rootCause": "Expired TLS certificate on the branch AnyConnect VPN gateway",
        "stepsTaken": "Renewed the gateway certificate and restarted the VPN tunnel service",
        "preventiveAction": "Certificate expiry alert 30 days ahead",
        "confirmedCategory": "NETWORK"}), "resolve A")
    b = raise_ticket(base, employee, admin, "AnyConnect VPN tunnel to the branch gateway fails at %s" % run,
                     "My AnyConnect VPN tunnel to the branch gateway at %s fails with a certificate warning this morning." % run)
    print("ticket A (resolved) %s, ticket B (new) %s" % (a, b))

    hit = suggestion(base, technician, b, a)
    check(hit is not None and hit["workedVotes"] == 0 and abs(hit["score"] - hit["similarity"]) < 1e-9,
          "A is suggested for B without votes, score == similarity (%s)" % (hit and round(hit["score"], 4)))

    votes = "/api/technician/incidents/%s/suggested-resolutions/votes" % b
    status, body = call(base, "POST", votes, technician, {"sourceType": "RESOLVED_TICKET", "sourceId": a, "worked": True})
    check(status == 200 and (body["workedVotes"], body["didNotWorkVotes"]) == (1, 0), "first vote: HTTP %s %s" % (status, body))
    status, body = call(base, "POST", votes, technician, {"sourceType": "RESOLVED_TICKET", "sourceId": a, "worked": False})
    check(status == 200 and (body["workedVotes"], body["didNotWorkVotes"]) == (0, 1),
          "changed vote replaces the first: HTTP %s %s" % (status, body and (body["workedVotes"], body["didNotWorkVotes"])))
    for token in (lead, admin):
        must(call(base, "POST", votes, token, {"sourceType": "RESOLVED_TICKET", "sourceId": a, "worked": True}), "vote")

    hit = suggestion(base, technician, b, a)
    factor = 1 - VOTE_WEIGHT + 2 * VOTE_WEIGHT * (2 + 1) / (2 + 1 + 2)
    check(hit is not None and (hit["workedVotes"], hit["didNotWorkVotes"]) == (2, 1)
          and abs(hit["score"] - hit["similarity"] * factor) < 1e-9 and 0.8 < hit["score"] / hit["similarity"] < 1.2,
          "three voters: counts 2/1, score = similarity x %.3f (%s)" % (factor, hit and round(hit["score"], 4)))

    cases = [
        ("unknown source", technician, {"sourceType": "RESOLVED_TICKET", "sourceId": "NET-2099-999999", "worked": True}, None, 404, "ERR_NOT_FOUND"),
        ("article source before K3", technician, {"sourceType": "KB_ARTICLE", "sourceId": "KB-1", "worked": True}, None, 404, "ERR_NOT_FOUND"),
        ("unknown sourceType", technician, {"sourceType": "SOMETHING", "sourceId": a, "worked": True}, None, 400, "ERR_MALFORMED_JSON"),
        ("missing verdict", technician, {"sourceType": "RESOLVED_TICKET", "sourceId": a}, None, 400, "ERR_VALIDATION_FAILED"),
        ("broken JSON", technician, None, "{not json", 400, "ERR_MALFORMED_JSON"),
        ("other-team technician", other_team, {"sourceType": "RESOLVED_TICKET", "sourceId": a, "worked": True}, None, 403, "ERR_UNAUTHORIZED_OPERATION"),
        ("employee", employee, {"sourceType": "RESOLVED_TICKET", "sourceId": a, "worked": True}, None, 403, None),
        ("no token", None, {"sourceType": "RESOLVED_TICKET", "sourceId": a, "worked": True}, None, 401, None),
    ]
    for name, token, payload, raw, expected_status, expected_code in cases:
        status, body = call(base, "POST", votes, token, payload, raw)
        code = (body or {}).get("code")
        check(status == expected_status and (expected_code is None or code == expected_code),
              "%s: HTTP %s %s" % (name, status, code or ""))

    for ticket_id in (b,):
        finish(base, technician, ticket_id)
    print("%d check(s) failed" % len(FAILURES) if FAILURES else "all checks passed")
    sys.exit(1 if FAILURES else 0)


if __name__ == "__main__":
    main()
