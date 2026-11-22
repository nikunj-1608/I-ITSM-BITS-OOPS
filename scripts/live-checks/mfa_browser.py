#!/usr/bin/env python3
"""Real-browser check for ADR-048 (two-step verification for administrators), in Microsoft Edge.

Usage (application running in its default dev setup, rate limit off; Python with Playwright and Edge installed):

    mvn -o -Dmaven.test.skip=true spring-boot:run
    python scripts/live-checks/mfa_browser.py [--base http://localhost:8085] [--headed]

The account under test is the seeded IT manager (an administrator). Every step goes through the real forms and the Security
dialog; the one-time codes are computed here (RFC 6238, standard library). Two-step verification is switched off for the
account again at the end, also when a check fails.

  A. the Security button is shown to an administrator and not to an employee.
  B. Security dialog: set up (the secret and the link are shown as text), confirm with a code, the 8 recovery codes are shown.
  C. sign out and sign in again: the password step shows the code step instead of the app; a wrong code shows an error and
     keeps the user on the sign-in card; the right code signs in.
  D. sign out and sign in with a recovery code.
  E. Security dialog: turning it off needs the password and a code; afterwards sign-in needs no code.
  F. the dialog keeps focus inside (Tab trap) and no console error appears other than the 401 answers to wrong codes.

Exit code 0 when every check passes, 1 otherwise.
"""
import argparse
import base64
import hashlib
import hmac
import json
import struct
import sys
import time
import urllib.error
import urllib.request

from playwright.sync_api import sync_playwright

PASSWORD = "Password123!"
ADMIN = "admin@nimbusfinserve.com"
MANAGER = "manager@nimbusfinserve.com"
EMPLOYEE = "employee@nimbusfinserve.com"
STEP_SECONDS = 30
FAILURES = []


def check(ok, text):
    print("%-4s %s" % ("ok" if ok else "FAIL", text))
    if not ok:
        FAILURES.append(text)


def api(base, method, path, token=None, body=None):
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
        return error.code, None


def totp(secret_text, step):
    key = base64.b32decode(secret_text + "=" * (-len(secret_text) % 8))
    digest = hmac.new(key, struct.pack(">Q", step), hashlib.sha1).digest()
    offset = digest[-1] & 0x0F
    return "%06d" % ((struct.unpack(">I", digest[offset:offset + 4])[0] & 0x7FFFFFFF) % 1_000_000)


class Codes:
    def __init__(self, secret):
        self.secret = secret
        self.last = int(time.time()) // STEP_SECONDS - 2

    def next(self):
        step = self.last + 1
        while step > int(time.time()) // STEP_SECONDS + 1:
            time.sleep(1)
        self.last = step
        return totp(self.secret, step)

    def wrong(self):
        now = int(time.time()) // STEP_SECONDS
        valid = {totp(self.secret, s) for s in range(now - 3, now + 4)}
        return next("%06d" % n for n in range(1_000_000) if "%06d" % n not in valid)


def sign_in(page, base, email, password=PASSWORD):
    page.goto(base + "/")
    page.fill("#login-email", email)
    page.fill("#login-password", password)
    page.click("#btn-login-submit")


def sign_out(page):
    page.click("#btn-logout")
    page.wait_for_selector("#form-login", timeout=15000)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://localhost:8085")
    parser.add_argument("--headed", action="store_true")
    args = parser.parse_args()
    base = args.base

    status, admin = api(base, "POST", "/api/auth/login", None, {"email": ADMIN, "password": PASSWORD})
    manager_id = api(base, "POST", "/api/auth/login", None, {"email": MANAGER, "password": PASSWORD})[1]["userId"]
    admin_token = admin["token"]
    console_errors, enabled = [], False

    try:
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(channel="msedge", headless=not args.headed)
            page = browser.new_page()
            page.on("console", lambda m: console_errors.append(m.text) if m.type == "error" else None)

            # A. the button is for administrators
            sign_in(page, base, EMPLOYEE)
            page.wait_for_selector("#btn-logout", timeout=15000)
            check(not page.locator("#btn-security").is_visible(), "A. an employee does not see the Security button")
            sign_out(page)

            sign_in(page, base, MANAGER)
            page.wait_for_selector("#btn-logout", timeout=15000)
            check(page.locator("#btn-security").is_visible(), "A. an administrator sees the Security button")

            # B. set up and confirm
            page.click("#btn-security")
            page.wait_for_selector("#btn-sec-setup", timeout=10000)
            check("off" in page.inner_text("#security-modal"), "B. the dialog says two-step verification is off")
            page.click("#btn-sec-setup")
            page.wait_for_selector("#sec-secret", timeout=10000)
            secret = page.inner_text("#sec-secret").strip()
            uri = page.inner_text("#sec-uri").strip()
            check(len(secret) == 32 and uri.startswith("otpauth://totp/") and secret in uri,
                  "B. the secret and the otpauth link are shown as text")
            check(page.locator("#security-modal img, #security-modal canvas").count() == 0, "B. no QR image is drawn")
            codes = Codes(secret)
            page.fill("#sec-enable-code", codes.wrong())
            page.click("#btn-sec-enable")
            page.wait_for_selector("#sec-error:not(.hidden)", timeout=10000)
            check("not accepted" in page.inner_text("#sec-error"), "B. a wrong code shows an error and the dialog stays open")
            check(page.locator("#btn-logout").is_visible(), "B. a wrong code does not sign the user out")
            page.fill("#sec-enable-code", codes.next())
            page.click("#btn-sec-enable")
            page.wait_for_selector("#sec-recovery-codes", timeout=10000)
            recovery = [li.inner_text().strip() for li in page.locator("#sec-recovery-codes li").all()]
            enabled = True
            check(len(recovery) == 8 and all(len(c) == 10 for c in recovery), "B. 8 recovery codes of 10 characters are shown")

            # F. focus stays inside the dialog
            page.focus("#btn-sec-codes-saved")
            for _ in range(8):
                page.keyboard.press("Tab")
            check(page.evaluate("!!document.activeElement.closest('#modal-container')"), "F. Tab keeps the focus inside the dialog")
            page.click("#btn-sec-codes-saved")
            page.wait_for_selector("#form-sec-disable", timeout=10000)
            page.click("#security-modal [data-close-modal]")
            page.wait_for_selector("#modal-container", state="hidden", timeout=10000)
            sign_out(page)

            # C. second step
            sign_in(page, base, MANAGER)
            page.wait_for_selector("#form-mfa:not(.hidden)", timeout=10000)
            check(page.locator("#btn-logout").is_visible() is False, "C. the password step alone does not open the app")
            check(not page.locator("#form-login").is_visible(), "C. the password form is replaced by the code step")
            page.fill("#mfa-code", codes.wrong())
            page.click("#btn-mfa-submit")
            page.wait_for_selector("#mfa-error-alert:not(.hidden)", timeout=10000)
            check(page.locator("#form-mfa").is_visible() and not page.locator("#btn-logout").is_visible(),
                  "C. a wrong code shows an error and stays on the sign-in card")
            page.fill("#mfa-code", codes.next())
            page.click("#btn-mfa-submit")
            page.wait_for_selector("#btn-logout", timeout=15000)
            check(page.locator("#btn-security").is_visible(), "C. the right code signs in")
            sign_out(page)
            check(page.locator("#form-login").is_visible() and not page.locator("#form-mfa").is_visible(),
                  "C. after signing out the sign-in card shows the password form again")

            # D. recovery code
            sign_in(page, base, MANAGER)
            page.wait_for_selector("#form-mfa:not(.hidden)", timeout=10000)
            page.fill("#mfa-code", recovery[0].lower())
            page.click("#btn-mfa-submit")
            page.wait_for_selector("#btn-logout", timeout=15000)
            check(True, "D. a recovery code signs in")
            sign_out(page)
            sign_in(page, base, MANAGER)
            page.wait_for_selector("#form-mfa:not(.hidden)", timeout=10000)
            page.fill("#mfa-code", recovery[0])
            page.click("#btn-mfa-submit")
            page.wait_for_selector("#mfa-error-alert:not(.hidden)", timeout=10000)
            check(not page.locator("#btn-logout").is_visible(), "D. the same recovery code a second time is refused")
            page.click("#btn-mfa-cancel")
            check(page.locator("#form-login").is_visible(), "D. Back returns to the password form")

            # E. turn it off
            page.fill("#login-password", PASSWORD)
            page.click("#btn-login-submit")
            page.wait_for_selector("#form-mfa:not(.hidden)", timeout=10000)
            page.fill("#mfa-code", recovery[1])
            page.click("#btn-mfa-submit")
            page.wait_for_selector("#btn-logout", timeout=15000)
            page.click("#btn-security")
            page.wait_for_selector("#form-sec-disable", timeout=10000)
            page.fill("#sec-disable-password", "wrong-password")
            page.fill("#sec-disable-code", codes.wrong())
            page.click("#btn-sec-disable")
            page.wait_for_selector("#sec-error:not(.hidden)", timeout=10000)
            check("password" in page.inner_text("#sec-error").lower(), "E. turning it off with a wrong password is refused")
            page.fill("#sec-disable-password", PASSWORD)
            page.fill("#sec-disable-code", codes.next())
            page.click("#btn-sec-disable")
            page.wait_for_selector("#btn-sec-setup", timeout=10000)
            enabled = False
            check(True, "E. the right password and a code turn it off")
            page.click("#security-modal [data-close-modal]")
            sign_out(page)
            sign_in(page, base, MANAGER)
            page.wait_for_selector("#btn-logout", timeout=15000)
            check(not page.locator("#form-mfa").is_visible(), "E. afterwards sign-in needs no code")
            sign_out(page)

            unexpected = [e for e in console_errors if not any(code in e for code in ("401", "404"))]
            check(not unexpected, "F. no console error other than the 401 answers and the favicon 404 (%s)" % unexpected)
            browser.close()
    finally:
        if enabled:
            api(base, "POST", "/api/admin/users/%s/mfa/reset" % manager_id, admin_token, {})
        api(base, "POST", "/api/admin/users/%s/unlock" % manager_id, admin_token, {})

    print("\n%d failure(s)" % len(FAILURES))
    sys.exit(1 if FAILURES else 0)


if __name__ == "__main__":
    main()
