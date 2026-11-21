#!/usr/bin/env python3
"""Live check for ADR-037 (actors are user ids, staff views show names, the employee history is neutral).

Usage (application running and seeded, e.g. `mvn -o -Dmaven.test.skip=true spring-boot:run`; Python with Playwright and
Microsoft Edge installed for the browser part):

    python scripts/live-checks/actors_check.py [--base http://localhost:8085] [--headed] [--no-browser]

What it does
  1. Through the API: an employee reports a ticket; a second, identical report becomes its duplicate; the administrator
     assigns the first to a technician of the Network team and overrides its priority; the technician acknowledges,
     starts, adds a note and resolves; the employee verifies.
  2. The administrator's detail view: no actor in the audit trail is an e-mail address; the override, the resolver and
     the audit actors carry their names; the note author is the technician's id and name.
  3. The employee's views (list and detail of both tickets): no ticket id other than their own, none of the technician's
     reason text, and the duplicate and reassignment entries use the neutral wording.
  4. In a real browser as the administrator: open the ticket dialog; the override callout, the resolution and the audit
     timeline show "Name (id)"; no console error.

Exit code 0 when every check passes, 1 otherwise.
"""
import argparse
import json
import random
import string
import sys
import urllib.error
import urllib.request

PASSWORD = "Password123!"
EMPLOYEE = "employee@nimbusfinserve.com"
ADMIN = "admin@nimbusfinserve.com"
REASON = "Belongs to the cloud platform team, not to network operations"
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


def must(status_body, what):
    status, body = status_body
    if status not in (200, 201, 204):
        sys.exit("FAIL: %s answered HTTP %s %s" % (what, status, body))
    return body


def words(rng, n):
    return " ".join("".join(rng.choice(string.ascii_lowercase) for _ in range(9)) for _ in range(n))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default="http://localhost:8085")
    parser.add_argument("--headed", action="store_true")
    parser.add_argument("--no-browser", action="store_true")
    args = parser.parse_args()
    base = args.base
    rng = random.Random()

    employee, admin = login(base, EMPLOYEE), login(base, ADMIN)
    roster = must(call(base, "GET", "/api/admin/technicians", admin), "technician roster")
    network = [t for t in roster if t["supportTeamId"] == "TEAM-NET" and t["role"] == "ROLE_TECHNICIAN"]
    technician = network[0]
    technician_token = login(base, technician["email"])
    admin_name = must(call(base, "GET", "/api/auth/me", admin), "me")["name"]

    title = "VPN gateway timeouts " + words(rng, 1)
    description = "The vpn gateway " + words(rng, 12) + " vpn tunnel drops."
    first = must(call(base, "POST", "/api/employee/incidents", employee, {"title": title, "description": description}), "report 1")["id"]
    second = must(call(base, "POST", "/api/employee/incidents", employee, {"title": title, "description": description}), "report 2")["id"]

    must(call(base, "POST", "/api/admin/incidents/%s/assign" % first, admin, {"technicianId": technician["id"]}), "assign")
    must(call(base, "POST", "/api/admin/incidents/%s/override-priority" % first, admin,
              {"priority": "P2", "reason": "Several departments are affected"}), "override")
    for step in ("acknowledge", "start-investigation"):
        must(call(base, "POST", "/api/technician/incidents/%s/%s" % (first, step), technician_token), step)
    must(call(base, "POST", "/api/technician/incidents/%s/notes" % first, technician_token,
              {"noteText": "Checked the gateway", "internalOnly": True}), "note")
    must(call(base, "POST", "/api/technician/incidents/%s/resolve" % first, technician_token,
              {"rootCause": "Gateway firmware bug", "stepsTaken": "Rolled the gateway back", "confirmedCategory": "NETWORK"}), "resolve")
    must(call(base, "POST", "/api/employee/incidents/%s/verify" % first, employee), "verify")
    print("setup: tickets %s (handled by %s) and its duplicate %s" % (first, technician["id"], second))

    # --- 2. the administrator's view
    detail = must(call(base, "GET", "/api/admin/incidents/" + first, admin), "admin detail")
    actors = [r["actorId"] for r in detail["auditTrail"]]
    check(actors and not any("@" in a for a in actors), "audit trail: no actor is an e-mail address %s" % sorted(set(actors)))
    check(detail["priorityDecision"]["overriddenBy"] == "ADMIN-SYS-01"
          and detail["priorityDecision"]["overriddenByName"] == admin_name, "override: stored id, name '%s'" % admin_name)
    check(detail["resolution"]["resolvedBy"] == technician["id"]
          and detail["resolution"]["resolvedByName"] == technician["name"], "resolver: stored id, name '%s'" % technician["name"])
    check(detail["notes"][0]["authorId"] == technician["id"] and detail["notes"][0]["authorName"] == technician["name"],
          "note author: the technician's id and name")
    named = [r for r in detail["auditTrail"] if r["actorId"] == technician["id"]]
    check(named and all(r["actorName"] == technician["name"] for r in named), "audit actors that are users carry their name")

    # --- 3. the employee's views
    other_ids = {first, second}
    views = list(must(call(base, "GET", "/api/employee/incidents", employee), "employee list"))
    views += [must(call(base, "GET", "/api/employee/incidents/" + t, employee), "employee detail") for t in (first, second)]
    leaks = []
    for view in views:
        text = json.dumps(view)
        for other in other_ids - {view["id"]}:
            if other in text:
                leaks.append("%s names %s" % (view["id"], other))
        if REASON in text or "requested reassignment" in text or "Marked as duplicate of" in text:
            leaks.append("%s quotes internal text" % view["id"])
    check(not leaks, "employee views (%d): no other ticket id, no technician reason, no duplicate wording %s" % (len(views), leaks))
    second_view = must(call(base, "GET", "/api/employee/incidents/" + second, employee), "employee detail")
    duplicate = [e for e in second_view["auditTrail"] if e["action"] == "DuplicateDetected"]
    check(duplicate and duplicate[0]["description"] == "Linked to an earlier report of the same problem",
          "employee: the duplicate entry reads neutrally %s" % [e["description"] for e in duplicate])

    # --- 4. browser
    if not args.no_browser:
        from playwright.sync_api import sync_playwright
        errors = []
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(channel="msedge", headless=not args.headed)
            page = browser.new_page()
            page.on("console", lambda m: errors.append(m.text) if m.type == "error" and not m.text.startswith("Failed to load resource") else None)
            page.on("pageerror", lambda e: errors.append(str(e)))
            page.goto(base + "/")
            page.fill("#login-email", ADMIN)
            page.fill("#login-password", PASSWORD)
            page.click("#form-login button[type=submit]")
            page.wait_for_selector("#btn-logout", timeout=15000)
            page.click("button.nav-tab[data-target=console-admin]")
            page.fill("#admin-search-text", first)
            page.click("#btn-admin-search")
            page.locator(".btn-admin-view-ticket", has_text=first).first.click()
            page.wait_for_selector("#admin-ticket-detail-modal:not(.hidden)", timeout=15000)
            page.wait_for_function("() => document.querySelector('#td-resolution').innerText.includes('Resolved by')", timeout=15000)
            override_text = page.locator("#td-priority-overrides").inner_text()
            resolution_text = page.locator("#td-resolution").inner_text()
            audit_text = page.locator("#td-audit").inner_text()
            check("%s (ADMIN-SYS-01)" % admin_name in override_text, "browser: override callout shows '%s (ADMIN-SYS-01)'" % admin_name)
            check("%s (%s)" % (technician["name"], technician["id"]) in resolution_text,
                  "browser: resolution shows '%s (%s)'" % (technician["name"], technician["id"]))
            check("by %s (%s)" % (technician["name"], technician["id"]) in audit_text and "@" not in audit_text.replace("@nimbus", ""),
                  "browser: audit timeline shows names with ids, no e-mail actor")
            browser.close()
        check(not errors, "browser: no console error %s" % errors)

    if FAILURES:
        print("FAILED: %d check(s)" % len(FAILURES))
        sys.exit(1)
    print("OK: actors are ids, staff see names, employees see a neutral history")


if __name__ == "__main__":
    main()
