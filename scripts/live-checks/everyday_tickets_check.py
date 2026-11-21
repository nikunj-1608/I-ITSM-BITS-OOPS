#!/usr/bin/env python3
"""Live check for ADR-052 (everyday tickets are assigned automatically).

Usage (application running and seeded, e.g. `mvn -o -Dmaven.test.skip=true spring-boot:run`; best on a fresh start,
because tickets left open by an earlier run become duplicate parents of the next run's tickets):

    python scripts/live-checks/everyday_tickets_check.py [--base http://localhost:8085] [--target 0.90]

Reports every ticket of src/test/resources/classification/everyday_tickets.tsv (the same file the Maven benchmark
EverydayTicketBenchmarkTest reads) through the employee API, then reads each one back as the administrator and gives
it a verdict:

    AUTO_RIGHT_TEAM   a clear ticket got a technician of the team that handles its expected category (the goal)
    MISSED_TO_TRIAGE  a clear ticket was left in the triage queue without a technician (the reported problem)
    AUTO_WRONG_TEAM   a clear ticket got a technician of another team (the worst outcome)
    KEPT_FOR_PERSON   a vague, non-IT or mixed ticket was left for a person (correct)
    LEAKED            a vague or non-IT ticket got a technician anyway
    WAITING           a confident ticket without a technician and not in triage (its team is full; not a classifier issue)

Checks that always apply: every report is accepted, and every ticket without a technician that the detail view marks
as in triage is also listed in the administrator's triage queue. With --target, the share of clear tickets that are
AUTO_RIGHT_TEAM must reach it and no ticket may be AUTO_WRONG_TEAM or LEAKED (Phase 6 of ADR-052); without it the
totals are only reported. Exit code 0 when every check passes, 1 otherwise. Standard library only.
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

PASSWORD = "Password123!"
EMPLOYEE = "employee@nimbusfinserve.com"
ADMIN = "admin@nimbusfinserve.com"
TICKETS_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "src", "test", "resources",
                            "classification", "everyday_tickets.tsv")
TEAM_OF_CATEGORY = {
    "NETWORK": "TEAM-NET", "DATABASE": "TEAM-DB", "STORAGE": "TEAM-DB", "APPLICATION": "TEAM-APP",
    "SOFTWARE": "TEAM-APP", "SECURITY": "TEAM-SEC", "AUTHENTICATION": "TEAM-SEC", "CLOUD": "TEAM-CLOUD",
    "HARDWARE": "TEAM-DESK",
}
VERDICTS = ["AUTO_RIGHT_TEAM", "MISSED_TO_TRIAGE", "AUTO_WRONG_TEAM", "KEPT_FOR_PERSON", "LEAKED", "WAITING"]
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


def load_tickets():
    """Rows of the TSV: (id, expected category or None, accepts triage, title, description)."""
    rows = []
    with open(TICKETS_FILE, encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line or line.startswith("#") or line.startswith("id\t"):
                continue
            fields = line.split("\t")
            label = fields[1]
            or_triage = label.endswith("|TRIAGE")
            category = label.split("|")[0]
            rows.append((fields[0], None if category == "TRIAGE" else category, or_triage, fields[2], fields[3]))
    return rows


def verdict(expected, or_triage, in_triage, assignee_team):
    if expected is None:
        return "LEAKED" if assignee_team else "KEPT_FOR_PERSON"
    if in_triage:
        return "KEPT_FOR_PERSON" if or_triage else "MISSED_TO_TRIAGE"
    if assignee_team is None:
        return "WAITING"
    return "AUTO_RIGHT_TEAM" if assignee_team == TEAM_OF_CATEGORY[expected] else "AUTO_WRONG_TEAM"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default="http://localhost:8085")
    parser.add_argument("--target", type=float, default=None,
                        help="required share of clear tickets auto-routed to the right team (Phase 6: 0.90)")
    args = parser.parse_args()
    employee, admin = login(args.base, EMPLOYEE), login(args.base, ADMIN)
    status, roster = call(args.base, "GET", "/api/admin/technicians", admin)
    team_of_technician = {t["id"]: t["supportTeamId"] for t in roster}

    created = []
    for ticket_id, expected, or_triage, title, description in load_tickets():
        status, ticket = call(args.base, "POST", "/api/employee/incidents", employee,
                              {"title": title, "description": description})
        check(status in (200, 201), "%s report accepted (HTTP %s)" % (ticket_id, status))
        if status in (200, 201):
            created.append((ticket_id, expected, or_triage, title, ticket["id"]))
    time.sleep(1.0)  # the assignment is a second step right after the intake

    status, queue = call(args.base, "GET", "/api/admin/triage-queue", admin)
    in_queue = {item.get("incidentId") for item in (queue or [])}
    counts = dict.fromkeys(VERDICTS, 0)
    for row_id, expected, or_triage, title, incident_id in created:
        status, detail = call(args.base, "GET", "/api/admin/incidents/" + incident_id, admin)
        assignee = detail.get("assigneeId")
        in_triage = bool(detail.get("needsTriage")) and assignee is None
        if in_triage:
            check(incident_id in in_queue, "%s %s is in the administrator's triage queue" % (row_id, incident_id))
        result = verdict(expected, or_triage, in_triage, team_of_technician.get(assignee) if assignee else None)
        counts[result] += 1
        print("     %-16s %s expected=%-14s got=%-14s technician=%-12s | %s"
              % (result, row_id, (expected or "TRIAGE") + ("|TRIAGE" if or_triage else ""),
                 detail.get("category"), assignee, title))

    clear = sum(1 for row in created if row[1] is not None and not row[2])
    share = counts["AUTO_RIGHT_TEAM"] / clear if clear else 1.0
    print()
    print("clear tickets auto-routed to the right team: %d/%d (%.1f %%)" % (counts["AUTO_RIGHT_TEAM"], clear, 100 * share))
    for name in VERDICTS[1:]:
        print("%-17s %d" % (name, counts[name]))
    if args.target is not None:
        check(share >= args.target, "share of clear tickets auto-routed to the right team %.3f >= %.2f" % (share, args.target))
        check(counts["AUTO_WRONG_TEAM"] == 0, "no clear ticket was routed to a wrong team")
        check(counts["LEAKED"] == 0, "no vague or non-IT ticket was routed to a technician")

    if FAILURES:
        print("FAILED: %d check(s)" % len(FAILURES))
        sys.exit(1)
    print("OK")


if __name__ == "__main__":
    main()
