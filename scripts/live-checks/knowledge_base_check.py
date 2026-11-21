#!/usr/bin/env python3
"""Live check for ADR-042 (knowledge base, Phase K3).

Usage (application running and seeded, e.g. `mvn -o -Dmaven.test.skip=true spring-boot:run`):

    python scripts/live-checks/knowledge_base_check.py [--base http://localhost:8085]

What it does
  1. The employee reports a VPN outage whose text names them, their e-mail and department (ticket A); TECH-NET-1
     resolves it with a root cause that names them too.
  2. TECH-NET-1 promotes A: 201, id KB-<A>, technical content kept, the reporter's name and e-mail removed (the
     department is checked when /api/auth/me returns one; KnowledgeBaseRegressionTest always checks it).
  3. A second promotion is 409 ERR_ALREADY_PROMOTED; an open ticket is 409 ERR_NOT_RESOLVED; another team's technician
     403; the employee 403; no token 401; an unknown ticket 404; a missing sourceIncidentId 400.
  4. A similar ticket B gets A's article as a suggestion (type KB_ARTICLE) and not ticket A itself; a vote on the article
     is counted.

Exit code 0 when every check passes, 1 otherwise. Standard library only. Tickets and the article stay in the database.
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
    other_team = login(base, "tech.desk1@nimbusfinserve.com")
    me = must(call(base, "GET", "/api/auth/me", employee), "me")
    name, email, department = me["name"], me["email"], me.get("department") or ""
    print("reporter: %s <%s>, %s" % (name, email, department))

    # Four words only this run uses, shared by A and B. Earlier runs leave resolved tickets with the same wording in the
    # database (the open tickets of a run are resolved at its end); unseen words weigh most in TF-IDF, so sharing four of
    # them puts this run's A above those clones, which carry other unique words and tie each other on the rest.
    run = " ".join("".join(random.choice(string.ascii_lowercase) for _ in range(8)) for _ in range(4))
    a = raise_ticket(base, employee, admin, "VPN gateway at %s rejects AnyConnect for %s" % (run, name),
                     "Hello, this is %s (%s, %s). The AnyConnect VPN gateway at %s refuses my tunnel with a certificate error."
                     % (name, email, department, run))
    for step in ("acknowledge", "start-investigation"):
        must(call(base, "POST", "/api/technician/incidents/%s/%s" % (a, step), technician), step)
    must(call(base, "POST", "/api/technician/incidents/%s/resolve" % a, technician, {
        "rootCause": "Expired TLS certificate on the AnyConnect VPN gateway, reported by %s" % name,
        "stepsTaken": "Renewed the gateway certificate and restarted the VPN tunnel service; told %s" % email,
        "preventiveAction": "Certificate expiry alert 30 days ahead",
        "confirmedCategory": "NETWORK"}), "resolve A")
    print("ticket A (resolved) %s" % a)

    status, article = call(base, "POST", "/api/technician/knowledge-articles", technician, {"sourceIncidentId": a})
    check(status == 201 and article and article["id"] == "KB-" + a, "resolver promotes A: HTTP %s %s" % (status, article and article.get("id")))
    text = json.dumps(article).lower()
    personal = [part.lower() for part in name.split() if len(part) >= 3] + [email.lower()]
    if department:
        personal.append(department.lower())
    leaked = [p for p in personal if p in text]
    check(status == 201 and not leaked and "anyconnect vpn gateway" in text,
          "article keeps the technical text and no name, e-mail or department (leaked: %s)" % leaked)

    open_ticket = raise_ticket(base, employee, admin, "AnyConnect VPN drops at the gateway",
                               "The VPN gateway drops my AnyConnect tunnel every few minutes from home.")
    cases = [
        ("second promotion", admin, {"sourceIncidentId": a}, 409, "ERR_ALREADY_PROMOTED"),
        ("open ticket", admin, {"sourceIncidentId": open_ticket}, 409, "ERR_NOT_RESOLVED"),
        ("other-team technician", other_team, {"sourceIncidentId": a}, 403, "ERR_UNAUTHORIZED_OPERATION"),
        ("employee", employee, {"sourceIncidentId": a}, 403, None),
        ("no token", None, {"sourceIncidentId": a}, 401, None),
        ("unknown ticket", admin, {"sourceIncidentId": "NET-2099-999999"}, 404, "ERR_SERVICE_NOT_FOUND"),
        ("missing sourceIncidentId", admin, {"title": "x"}, 400, "ERR_VALIDATION_FAILED"),
    ]
    for label, token, body, expected_status, expected_code in cases:
        status, answer = call(base, "POST", "/api/technician/knowledge-articles", token, body)
        code = (answer or {}).get("code")
        check(status == expected_status and (expected_code is None or code == expected_code),
              "%s: HTTP %s %s" % (label, status, code or ""))

    b = raise_ticket(base, employee, admin, "AnyConnect VPN tunnel fails with certificate error at %s" % run,
                     "Since this morning the VPN gateway at %s refuses my AnyConnect tunnel and shows a certificate warning." % run)
    suggestions = must(call(base, "GET", "/api/technician/incidents/%s/suggested-resolutions" % b, technician), "suggestions")
    hit = next((s for s in suggestions if s["sourceId"] == "KB-" + a), None)
    check(hit is not None and hit["sourceType"] == "KB_ARTICLE",
          "B's suggestions contain A's article (%s)" % [
              "%s sim=%.3f score=%.3f votes=%s/%s" % (s["sourceId"], s["similarity"], s["score"], s["workedVotes"],
                                                      s["didNotWorkVotes"]) for s in suggestions])
    check(all(s["sourceId"] != a for s in suggestions), "ticket A itself is not offered again")
    status, vote = call(base, "POST", "/api/technician/incidents/%s/suggested-resolutions/votes" % b, technician,
                        {"sourceType": "KB_ARTICLE", "sourceId": "KB-" + a, "worked": True})
    check(status == 200 and vote["workedVotes"] == 1, "vote on the article: HTTP %s %s" % (status, vote and vote.get("workedVotes")))

    for ticket_id in (open_ticket, b):
        finish(base, technician, ticket_id)
    print("%d check(s) failed" % len(FAILURES) if FAILURES else "all checks passed")
    sys.exit(1 if FAILURES else 0)


if __name__ == "__main__":
    main()
