"""Login page redesign (Swiftdesk logo, no role dropdown): real-browser check in Microsoft Edge.

Start the app first (dev profile, port 8085, rate limit off):  .\\mvnw spring-boot:run
Then:  python scripts/live-checks/login_page_browser.py [base_url]

Checks the page structure, the show/hide password button, a wrong password, a sign-in for each role through the real
form, sign-out landing on the sign-in card with the password hidden again, and that nothing is written to the console.
"""
import sys
from playwright.sync_api import sync_playwright

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8085"
PASSWORD = "Password123!"
ROLES = [
    ("employee@nimbusfinserve.com", "employee"),
    ("tech.network@nimbusfinserve.com", "technician"),
    ("lead.network@nimbusfinserve.com", "team lead"),
    ("admin@nimbusfinserve.com", "administrator"),
]

results = []


def check(name, ok, detail=""):
    results.append(ok)
    print(("PASS " if ok else "FAIL ") + name + (f"  [{detail}]" if detail and not ok else ""))


def sign_out(page):
    page.click("#btn-logout")
    page.wait_for_selector("#login-card:not(.hidden)")


with sync_playwright() as p:
    browser = p.chromium.launch(channel="msedge")
    page = browser.new_page(viewport={"width": 1280, "height": 900})
    console_problems = []
    page.on("pageerror", lambda e: console_problems.append("pageerror: " + str(e)))
    # A failed request is reported with its URL. Expected and ignored: the wrong-password sign-in (401) and the favicon
    # (the page declares none, CLAUDE.md section 7 item 15).
    page.on("response", lambda r: console_problems.append(f"{r.status} {r.url}")
            if r.status >= 400 and not r.url.endswith("/favicon.ico")
            and not (r.status == 401 and r.url.endswith("/api/auth/login")) else None)

    page.goto(BASE + "/")
    page.wait_for_selector("#login-card")

    # structure
    check("logo shown", page.locator("#login-card img.login-logo[alt='Swiftdesk']").count() == 1)
    check("logo loaded", page.evaluate("document.querySelector('.login-logo').naturalWidth") > 0)
    check("no role dropdown on the card", page.locator("#login-card select").count() == 0)
    check("no forgot-password link", page.locator("#login-card").get_by_text("Forgot", exact=False).count() == 0)
    check("no self-register button", page.locator("#btn-toggle-register").count() == 0)
    check("no demo accounts list", page.locator("#demo-accounts-container, .demo-pill").count() == 0)
    check("remember-me visible", page.locator("#login-remember").is_visible()
          and "Remember me for 30 days" in page.locator(".login-remember").inner_text())
    check("labels", page.locator("label[for=login-email]").inner_text() == "EMAIL ADDRESS"
          and page.locator("label[for=login-password]").inner_text() == "PASSWORD")

    # show / hide password
    page.fill("#login-password", "secret-123")
    check("password hidden at start", page.get_attribute("#login-password", "type") == "password")
    page.click("#btn-login-toggle-password")
    check("eye shows the password", page.get_attribute("#login-password", "type") == "text"
          and page.get_attribute("#btn-login-toggle-password", "aria-pressed") == "true"
          and page.get_attribute("#btn-login-toggle-password", "aria-label") == "Hide password")
    check("value kept while toggling", page.input_value("#login-password") == "secret-123")
    page.click("#btn-login-toggle-password")
    check("eye hides it again", page.get_attribute("#login-password", "type") == "password"
          and page.get_attribute("#btn-login-toggle-password", "aria-label") == "Show password")

    # the remember box is display only: ticking it changes nothing about the sign-in
    page.check("#login-remember")

    # wrong password
    page.fill("#login-email", "employee@nimbusfinserve.com")
    page.fill("#login-password", "definitely-wrong")
    page.click("#btn-login-submit")
    page.wait_for_selector("#login-error-alert:not(.hidden)")
    check("wrong password shows the error", "Sign-in Failed" in page.inner_text("#login-error-alert"))
    check("Sign In button restored with its arrow", page.locator("#btn-login-submit svg").count() == 1
          and page.locator("#btn-login-submit").inner_text().strip() == "Sign In"
          and page.locator("#btn-login-submit").is_enabled())

    # every role through the real form, then sign out
    for email, label in ROLES:
        page.fill("#login-email", email)
        page.fill("#login-password", PASSWORD)
        page.click("#btn-login-toggle-password")          # leave the password visible on purpose
        page.click("#btn-login-submit")
        page.wait_for_selector("#app-view:not(.hidden)", timeout=15000)
        check(f"sign-in as {label}", page.is_visible("#user-display-name"))
        sign_out(page)
        check(f"sign-out from {label} shows the sign-in card",
              page.is_visible("#login-card") and page.is_hidden("#register-card"))
        check(f"password hidden again after {label} signs out",
              page.get_attribute("#login-password", "type") == "password")

    check("no console errors", not console_problems, "; ".join(console_problems))
    page.screenshot(path="login_page_browser.png")
    browser.close()

print(f"\n{sum(results)} of {len(results)} checks passed")
sys.exit(0 if all(results) else 1)
