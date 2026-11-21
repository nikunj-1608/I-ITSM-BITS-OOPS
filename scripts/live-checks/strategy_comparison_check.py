#!/usr/bin/env python3
"""Live check for ADR-044 Phase L3 (offline strategy comparison): endpoint, no side effects, and the Simulation tab panel.

Usage (application running and seeded; for the browser part Python with Playwright and Microsoft Edge installed):

    mvn -o -Dmaven.test.skip=true spring-boot:run
    python scripts/live-checks/strategy_comparison_check.py [--base http://localhost:8085] [--headed] [--no-browser]

API (as the administrator):
  defaults    an empty body runs the defaults on the seeded roster in a few seconds: five strategies, eight metrics, the
              assumptions and the "not production data" notice; the run time is printed
  determinism the same request twice gives the same answer (except the run time); another seed does not
  errors      every bad value is HTTP 400 ERR_VALIDATION_FAILED naming the field; broken JSON is ERR_MALFORMED_JSON
  access      no token 401; technician, team lead and employee 403
  no effects  the number of tickets, the active assignment strategy, the clock speed, the technicians' open tickets and
              the triage queue are the same before and after a request (retried: background work can move them once)
Browser (Edge, real login form, as the administrator):
  the panel is in the Simulation tab; a run shows the results table with the best value of every column highlighted, the
  differences marked, the notice and the assumptions; a second click while it runs sends ONE request; a bad value shows
  a friendly message and sends nothing; signing out clears the result; no console error

Exit code 0 when every check passes, 1 otherwise. Standard library for the API part; Playwright for the browser part.
"""
import argparse
import json
import sys
import time
import urllib.error
import urllib.request

PASSWORD = "Password123!"
ADMIN = "admin@nimbusfinserve.com"
URL = "/api/simulation/strategy-comparison"
FAILURES = []


def call(base, method, path, token=None, body=None, raw=None):
    data = raw.encode("utf-8") if raw is not None else (None if body is None else json.dumps(body).encode("utf-8"))
    request = urllib.request.Request(base + path, data=data, method=method)
    request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            text = response.read().decode("utf-8")
            return response.status, (json.loads(text) if text else None)
    except urllib.error.HTTPError as error:
        text = error.read().decode("utf-8")
        try:
            return error.code, json.loads(text)
        except ValueError:
            return error.code, text


def login(base, email):
    status, body = call(base, "POST", "/api/auth/login", body={"email": email, "password": PASSWORD})
    if status != 200 or "token" not in (body or {}):
        sys.exit("FAIL: cannot log in as %s (HTTP %s)" % (email, status))
    return body["token"]


def check(ok, text):
    print("%-4s %s" % ("ok" if ok else "FAIL", text))
    if not ok:
        FAILURES.append(text)


def without_run_time(answer):
    return {k: v for k, v in answer.items() if k != "runMillis"}


def counts(base, admin):
    """Things a simulation must not change, read through the administrator's API."""
    _, stats = call(base, "GET", "/api/admin/stats", admin)
    _, strategies = call(base, "GET", "/api/admin/assignment/strategies", admin)
    _, clock = call(base, "GET", "/api/simulation/status", admin)
    _, roster = call(base, "GET", "/api/admin/technicians", admin)
    _, triage = call(base, "GET", "/api/admin/triage-queue", admin)
    return {
        "tickets": (stats or {}).get("totalIncidents", (stats or {}).get("total")),
        "active strategy": [s["name"] for s in (strategies or []) if s.get("active")],
        "clock speed": (clock or {}).get("speedMultiplier"),
        "open tickets of the technicians": sum(t["activeIncidentCount"] for t in (roster or [])),
        "triage queue": len(triage or []),
    }


def no_effects_probe(base, admin):
    """The state a simulation must not change, read around one request. The application has background work of its own
    (the dispatcher assigns waiting tickets every 30 s, the SLA scan runs), so a difference in one attempt can be that work;
    a real side effect of the comparison would show in every attempt, so the probe fails only when all attempts differ."""
    differences = []
    for attempt in range(3):
        before = counts(base, admin)
        status, _ = call(base, "POST", URL, admin, {"ticketCount": 100, "replications": 2})
        after = counts(base, admin)
        if status == 200 and after == before:
            check(True, "no effects: %s unchanged around a request (attempt %d)" % (json.dumps(before), attempt + 1))
            return
        differences.append({k: (before[k], after[k]) for k in before if before[k] != after[k]})
        time.sleep(1.0)
    check(False, "no effects: the state changed around every request: %s" % json.dumps(differences))


def api_checks(base, admin):
    started = time.time()
    status, first = call(base, "POST", URL, admin, {})
    elapsed = time.time() - started
    check(status == 200, "defaults: HTTP %s in %.1f s (server run time %s ms)" % (status, elapsed, (first or {}).get("runMillis")))
    if status != 200:
        sys.exit("FAIL: cannot continue without an answer: %s" % first)
    p = first["parameters"]
    check((p["seed"], p["ticketCount"], p["replications"], p["arrivalsPerHour"]) == (42, 500, 10, 15.0),
          "defaults: seed 42, 500 tickets, 10 replications, 15 per hour")
    check(len(first["strategies"]) == 5 and len(first["metrics"]) == 8 and first["poolSource"] == "ROSTER",
          "defaults: %d strategies, %d metrics, pool %s of %d technicians"
          % (len(first["strategies"]), len(first["metrics"]), first["poolSource"], first["technicianCount"]))
    check("not production data" in first["notice"] and len(first["assumptions"]) >= 6,
          "defaults: the notice says 'not production data' and %d assumptions are listed" % len(first["assumptions"]))
    check(elapsed < 15, "defaults: finished within a few seconds (%.1f s round trip)" % elapsed)
    check(all(len(v) >= 1 for v in first["winners"].values()), "defaults: every metric has a winner")

    status, second = call(base, "POST", URL, admin, {})
    check(status == 200 and without_run_time(second) == without_run_time(first), "determinism: the same request gives the same answer")
    status, other = call(base, "POST", URL, admin, {"seed": 43})
    check(status == 200 and without_run_time(other) != without_run_time(first), "determinism: another seed gives another answer")
    status, subset = call(base, "POST", URL, admin, {"ticketCount": 100, "replications": 2, "strategies": ["round_robin", "load_based"]})
    check(status == 200 and [s["name"] for s in subset["strategies"]] == ["LOAD_BASED", "ROUND_ROBIN"]
          and subset["referenceStrategy"] == "LOAD_BASED", "a subset by name: LOAD_BASED and ROUND_ROBIN, reference LOAD_BASED")


def error_checks(base, admin):
    cases = [({"ticketCount": 0}, "ticketCount"), ({"ticketCount": 5001}, "ticketCount"), ({"replications": 51}, "replications"),
             ({"arrivalsPerHour": 0}, "arrivalsPerHour"), ({"ticketCount": 5000, "replications": 6}, "ticketCount"),
             ({"strategies": ["FASTEST"]}, "strategies")]
    for body, field in cases:
        status, answer = call(base, "POST", URL, admin, body)
        check(status == 400 and answer.get("code") == "ERR_VALIDATION_FAILED" and ("'%s'" % field) in answer.get("message", ""),
              "error: %s -> HTTP %s %s naming '%s'" % (json.dumps(body), status, answer.get("code"), field))
    status, answer = call(base, "POST", URL, admin, raw="{not json")
    check(status == 400 and answer.get("code") == "ERR_MALFORMED_JSON", "error: broken JSON -> HTTP %s %s" % (status, answer.get("code")))
    status, answer = call(base, "POST", URL, admin, {"ticketCount": "many"})
    check(status == 400 and answer.get("code") == "ERR_MALFORMED_JSON", "error: a wrong type -> HTTP %s %s" % (status, answer.get("code")))


def access_checks(base):
    status, _ = call(base, "POST", URL, None, {})
    check(status == 401, "access: no token -> HTTP %s" % status)
    for email in ("tech.network@nimbusfinserve.com", "lead.network@nimbusfinserve.com", "employee@nimbusfinserve.com"):
        status, _ = call(base, "POST", URL, login(base, email), {})
        check(status == 403, "access: %s -> HTTP %s" % (email.split("@")[0], status))


def browser_checks(base, headed):
    from playwright.sync_api import sync_playwright
    errors, posts = [], []
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(channel="msedge", headless=not headed)
        page = browser.new_page()
        page.on("console", lambda m: errors.append(m.text)
                if m.type == "error" and not m.text.startswith("Failed to load resource") else None)
        page.on("pageerror", lambda e: errors.append(str(e)))
        page.on("request", lambda r: posts.append(r.url) if r.method == "POST" and "strategy-comparison" in r.url else None)
        page.goto(base + "/")
        page.fill("#login-email", ADMIN)
        page.fill("#login-password", PASSWORD)
        page.click("#form-login button[type=submit]")
        page.wait_for_selector("#btn-logout", timeout=15000)
        page.click("button.nav-tab[data-target=console-simulation]")
        page.wait_for_selector("#form-strategy-comparison", state="visible", timeout=10000)
        check(page.locator("#cmp-results").is_hidden() and page.locator("#btn-run-comparison").is_enabled(),
              "browser: the panel is in the Simulation tab, empty and ready")

        # a bad value shows a friendly message and sends nothing
        page.fill("#cmp-tickets", "0")
        page.click("#btn-run-comparison")
        page.wait_for_selector("#cmp-status:not(.hidden)", timeout=5000)
        check("Tickets per run" in page.inner_text("#cmp-status") and not posts, "browser: tickets 0 -> a friendly message, nothing sent")
        page.fill("#cmp-tickets", "5000")
        page.fill("#cmp-replications", "6")
        page.click("#btn-run-comparison")
        page.wait_for_selector("#cmp-status:not(.hidden)", timeout=5000)
        check("must not exceed" in page.inner_text("#cmp-status") and not posts, "browser: 5000 x 6 -> the product limit is explained, nothing sent")

        # a run: a double click and an Enter press send ONE request
        page.fill("#cmp-tickets", "400")
        page.fill("#cmp-replications", "6")
        page.click("#btn-run-comparison")
        page.keyboard.press("Enter")
        page.wait_for_selector("#cmp-results:not(.hidden) table.cmp-table", timeout=60000)
        check(len(posts) == 1, "browser: a click and an Enter while it runs sent %d request(s)" % len(posts))
        rows = page.locator("#cmp-results tbody tr")
        check(rows.count() == 5, "browser: the results table has %d strategy rows" % rows.count())
        columns = page.locator("#cmp-results thead th").count() - 1
        best = [page.locator("#cmp-results tbody td.td-num:nth-of-type(%d).cmp-best" % (c + 1)).count() for c in range(columns)]
        check(columns == 8 and all(b >= 1 for b in best), "browser: every one of the %d metric columns has a highlighted best value %s" % (columns, best))
        check(page.locator("#cmp-results .cmp-diff").count() == 40 and page.locator("#cmp-results .cmp-sig").count() >= 1,
              "browser: differences to the reference are shown and some are marked as larger than chance")
        check("not production data" in page.inner_text("#cmp-notice"), "browser: the notice says 'not production data'")
        check(page.locator("#cmp-assumptions li").count() >= 6, "browser: %d assumptions are listed" % page.locator("#cmp-assumptions li").count())
        check(page.locator("#btn-run-comparison").is_enabled() and page.locator("#cmp-status").is_hidden(),
              "browser: the button is enabled again and the status is gone")

        # the same input again gives the same table
        before = page.inner_text("#cmp-results")
        page.click("#btn-run-comparison")
        page.wait_for_function("() => document.querySelectorAll('#btn-run-comparison[aria-busy]').length === 0", timeout=60000)
        check(page.inner_text("#cmp-results") == before and len(posts) == 2, "browser: the same seed gives the same table")

        # signing out clears the result (administrator data)
        page.click("#btn-logout")
        page.wait_for_selector("#form-login", timeout=15000)
        page.fill("#login-email", ADMIN)
        page.fill("#login-password", PASSWORD)
        page.click("#form-login button[type=submit]")
        page.wait_for_selector("#btn-logout", timeout=15000)
        page.click("button.nav-tab[data-target=console-simulation]")
        check(page.locator("#cmp-results").is_hidden() and page.locator("#cmp-results").inner_text() == "",
              "browser: after signing out and in again the result is gone")
        browser.close()
    check(not errors, "browser: no console error %s" % errors)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default="http://localhost:8085")
    parser.add_argument("--headed", action="store_true")
    parser.add_argument("--no-browser", action="store_true")
    args = parser.parse_args()
    admin = login(args.base, ADMIN)
    api_checks(args.base, admin)
    no_effects_probe(args.base, admin)
    error_checks(args.base, admin)
    access_checks(args.base)
    if not args.no_browser:
        browser_checks(args.base, args.headed)
    if FAILURES:
        print("FAILED: %d check(s)" % len(FAILURES))
        sys.exit(1)
    print("OK: the comparison is correct, deterministic, administrator-only and changes nothing")


if __name__ == "__main__":
    main()
