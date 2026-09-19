import os
import sys
import time
import re
import pyotp
from faker import Faker
from camoufox.sync_api import Camoufox

LOGIN_EMAIL = "lokichone35@gmail.com"
LOGIN_PASS = "Somi@7015"
OTP_OPERATER = 2
URL = "https://m.uber.com/go/login-redirect"
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
OUTPUT_FILE = os.path.join(SCRIPT_DIR, "uber_accounts.txt")
PASSWORD_VALUE = "uber5555"
HEADLESS = False
PROMOTION_TEXT = "100% off your next ride"

def execute_with_retry(action_func, retries=3):
    last_exception = None
    for attempt in range(retries):
        try:
            return action_func()
        except Exception as e:
            last_exception = e
            time.sleep(1)
    raise last_exception

def initialize_output_file():
    if not os.path.exists(OUTPUT_FILE):
        with open(OUTPUT_FILE, "w", encoding="utf-8") as f:
            f.write("Generated Uber Accounts:\n\n")
            header = "{:<15}{:<24}{:<24}{:<40}{:<20}{}\n".format(
                "Serial No.", "Name", "Phone", "Authentication-Key", "Promotion Applied", "Verification Gateway"
            )
            f.write(header)
            f.write("=" * 150 + "\n\n")

def save_account(serial, name, phone, secret_key, promo_applied, gateway):
    row = "{:<15}{:<24}{:<24}{:<40}{:<20}{}\n".format(
        str(serial), name, phone, secret_key, promo_applied, gateway
    )
    with open(OUTPUT_FILE, "a", encoding="utf-8") as f:
        f.write(row)

def get_next_serial():
    if not os.path.exists(OUTPUT_FILE):
        return 1
    with open(OUTPUT_FILE, "r", encoding="utf-8") as f:
        lines = [
            line.strip()
            for line in f
            if line.strip()
            and not line.startswith("=")
            and not line.startswith("Generated")
            and not line.startswith("Serial")
        ]
    return len(lines) + 1

def setup_sms_panel(sms_page):
    print("[*] Setting up TechyIndia SMS Panel...")
    execute_with_retry(lambda: sms_page.goto("https://www.techyindia.org/login", wait_until="domcontentloaded", timeout=50000))
    print("[*] Entering credentials...")
    execute_with_retry(lambda: sms_page.locator("input[type='email'], input[name='email'], input").first.fill(LOGIN_EMAIL, timeout=50000))
    execute_with_retry(lambda: sms_page.locator("input[type='password']").first.fill(LOGIN_PASS, timeout=50000))
    execute_with_retry(lambda: sms_page.locator("button:has-text('Login')").click(timeout=50000))
    print("[*] Waiting for 'Connect With Us' popup...")
    try:
        ok_btn = sms_page.locator("button:has-text('OK, I Joined')")
        execute_with_retry(lambda: ok_btn.wait_for(state="visible", timeout=15000))
        execute_with_retry(lambda: ok_btn.click(timeout=5000))
    except Exception:
        print("[*] Popup not found or already closed.")
    print("[*] Navigating to Dashboard...")
    time.sleep(5)
    execute_with_retry(lambda: sms_page.goto("https://www.techyindia.org/", wait_until="domcontentloaded", timeout=50000))
    print(f"[*] Selecting OTP Operator{OTP_OPERATER}...")
    execute_with_retry(lambda: sms_page.get_by_text(f"OTP Operator{OTP_OPERATER}", exact=True).click(timeout=50000))
    print("[*] Selecting Service: Uber...")
    execute_with_retry(lambda: sms_page.locator("text=Select...").first.click(force=True, timeout=50000))
    execute_with_retry(lambda: sms_page.locator("text=Uber").first.click(force=True, timeout=50000))
    print("[*] Submitting...")
    execute_with_retry(lambda: sms_page.locator("button:has-text('Submit')").click(timeout=50000))
    print("[*] Waiting for Generate OTP page to load...")
    execute_with_retry(lambda: sms_page.locator("text=Generate Otp for Uber").wait_for(state="visible", timeout=50000))
    print("[+] SMS Panel ready!")

def get_single_number(sms_page, row_index):
    print(f"\n[*] Fetching a number from Row {row_index + 1}...")
    action_btns = sms_page.locator("button:has-text('Get Number'), button:has-text('Buy Next')").all()
    if not action_btns or row_index >= len(action_btns):
        print(f"[-] Button for row {row_index + 1} not found on the page.")
        return None
    row_ready = False
    while not row_ready:
        try:
            print(f"[*] Requesting number for Row {row_index + 1}...")
            execute_with_retry(lambda: action_btns[row_index].click(timeout=50000))
            time.sleep(1.5)
            error_popup = sms_page.locator("text=Sorry Due High Traffic").first
            if error_popup.is_visible(timeout=3000):
                print(f"[-] High traffic error on row {row_index + 1}. Waiting 6 seconds and retrying...")
                try:
                    execute_with_retry(lambda: sms_page.locator("button[aria-label='Close']").first.click(timeout=3000))
                except Exception:
                    pass
                time.sleep(6)
                continue
            phone_input = sms_page.locator("input[type='text'], input").nth(row_index)
            val = ""
            for _ in range(10):
                val = phone_input.input_value().strip()
                if val and len(val) >= 10 and any(c.isdigit() for c in val):
                    break
                time.sleep(1)
            if val and len(val) >= 10:
                print(f"[+] Successfully fetched: {val} (Row {row_index + 1})")
                return {"index": row_index, "phone": val, "fetch_time": time.time()}
            else:
                print(f"[-] Failed to load number on row {row_index + 1} without error. Retrying...")
                time.sleep(3)
        except Exception as e:
            print(f"[-] Error interacting with row {row_index + 1}: {e}")
            return None
    return None

def cancel_number(sms_page, current_number):
    index = current_number["index"]
    phone_number = current_number["phone"]
    fetch_time = current_number["fetch_time"]
    elapsed_time = time.time() - fetch_time
    if elapsed_time < 65:
        wait_time = 65 - elapsed_time
        print(f"[*] Cooldown active. Waiting {wait_time:.0f} seconds before cancel button is available for Row {index+1}...")
        time.sleep(wait_time)
    print(f"[*] Canceling number {phone_number} at Row {index+1}...")
    try:
        cancel_btns = sms_page.locator("button:has-text('Cancel')").all()
        if index < len(cancel_btns):
            try:
                execute_with_retry(lambda: cancel_btns[index].click(timeout=5000))
            except Exception:
                print(f"[-] Cancel button disabled or unclickable. Skipping click.")
            phone_input = sms_page.locator("input[type='text'], input").nth(index)
            is_cancelled = False
            for _ in range(10):
                if phone_input.input_value().strip() == "":
                    is_cancelled = True
                    break
                time.sleep(1)
            if is_cancelled:
                print(f"[+] Cancelled number: {phone_number} (Row {index+1})")
            else:
                print(f"[-] Row {index+1} did not visually clear. Manual verification may be required.")
        else:
            print(f"[-] Cancel button for row {index+1} not found.")
    except Exception as e:
        print(f"[-] Failed to cancel number: {e}")
    print("[*] Waiting 5 seconds before next action...")
    time.sleep(5)

def get_sms(sms_page, index, timeout=50):
    print(f"[*] Waiting for OTP in SMS Panel (Row {index+1})...")
    start_time = time.time()
    try:
        text_areas = sms_page.locator("textarea").all()
        if index >= len(text_areas):
            print(f"[-] Textarea for row {index+1} not found.")
            return None
        text_area = text_areas[index]
        while time.time() - start_time < timeout:
            val = text_area.input_value().strip()
            if val and "Otp will be shown here" not in val:
                match = re.search(r"\d{4}", val)
                if match:
                    return match.group(0)
            time.sleep(2)
    except Exception as e:
        print(f"[-] Error reading SMS: {e}")
    return None

def run_automation(faker, current_serial, sms_page, page, current_number, manual_name=None):
    phone_number = current_number["phone"]
    print(f"[*] Navigating to {URL}")
    try:
        execute_with_retry(lambda: page.goto(URL, wait_until="domcontentloaded", timeout=50000))
    except Exception:
        print("[-] Failed to load Uber URL. Discarding attempt.")
        return False
    try:
        phone_input = page.locator("#PHONE_NUMBER_or_EMAIL_ADDRESS, input[type='tel'], input[name='email'], input[placeholder*='phone' i]").first
        execute_with_retry(lambda: phone_input.wait_for(state="visible", timeout=50000))
        execute_with_retry(lambda: phone_input.click(timeout=50000))
        execute_with_retry(lambda: phone_input.fill(phone_number, timeout=50000))
        time.sleep(1)
        continue_btn = page.locator("button:has-text('Continue')").first
        execute_with_retry(lambda: continue_btn.click(timeout=50000))
        print("[+] Clicked Continue. Waiting for OTP screen...")
    except Exception as e:
        print(f"[-] Failed entering phone number: {e}")
        return False
    is_locked = False
    lock_indicators = [
        "text=Bad Request",
        "text=Unable to create account",
        "text=is blocked",
        "text=Account Locked",
        "text=Send us an SMS",
        "text=Open your security app",
        "text=This phone number is invalid",
        "button:has-text('Start Over')",
        "button:has-text('Choose another option')",
    ]
    otp_indicators = [
        "text=Enter the 4-digit",
        "text=Resend code",
    ]
    for _ in range(25):
        not_btn = page.locator(r"text=/Not .+\?/").first
        if not_btn.is_visible():
            print("[*] 'Not you?' detected before OTP. Clicking...")
            execute_with_retry(lambda: not_btn.click(timeout=5000))
            time.sleep(1)
        if page.locator("text=Welcome back").first.is_visible():
            send_sms_btn = page.get_by_text("Send code via SMS", exact=True).first
            if send_sms_btn.is_visible():
                print("[*] 'Welcome back' screen detected. Clicking 'Send code via SMS'...")
                execute_with_retry(lambda: send_sms_btn.click(timeout=5000))
                time.sleep(1)
        if any(page.locator(sel).first.is_visible() for sel in lock_indicators):
            is_locked = True
            break
        if any(page.locator(sel).first.is_visible() for sel in otp_indicators):
            break
        time.sleep(0.5)
    welcome_elem = (
        page.locator("text=Welcome back")
        .or_(page.locator("input[type='password']"))
        .first
    )
    has_bypass = (
        page.locator(r"text=/Not .+\?/").first.is_visible()
        or page.get_by_text("Send code via SMS", exact=True).first.is_visible()
    )
    if welcome_elem.is_visible() and not has_bypass:
        print("[-] 'Welcome back' without 'Not you?' or SMS option. Marking as locked...")
        is_locked = True
    if is_locked:
        print(f"[-] Phone {phone_number} is blocked or locked.")
        start_over_btn = page.locator("button:has-text('Start Over')").first
        if start_over_btn.is_visible():
            print("[*] Clicking 'Start Over' button...")
            try:
                execute_with_retry(lambda: start_over_btn.click(timeout=5000))
                time.sleep(1)
            except Exception:
                pass
        return False
    otp_input = page.locator("input[type='text'], input[maxlength='1'], input").first
    try:
        execute_with_retry(lambda: otp_input.wait_for(state="visible", timeout=50000))
    except Exception:
        print("[-] OTP Screen never loaded. Discarding attempt.")
        return False
    otp_code = get_sms(sms_page, current_number["index"], timeout=50)
    if page.locator("text=Bad Request").first.is_visible():
        print("[-] 'Bad Request' popup detected on OTP screen. Discarding attempt.")
        return False
    if not otp_code:
        print("[-] Timeout waiting for initial OTP. Clicking 'Resend code via SMS'...")
        resend_btn = page.locator("#alt-action-resend-sms").first
        try:
            execute_with_retry(lambda: resend_btn.wait_for(state="visible", timeout=5000))
            execute_with_retry(lambda: resend_btn.click(timeout=5000))
        except Exception:
            pass
        time.sleep(1)
        retry_btn = page.locator("button:has-text('Retry')").first
        try:
            if retry_btn.is_visible(timeout=5000):
                print("[*] Error popup detected. Clicking 'Retry'...")
                execute_with_retry(lambda: retry_btn.click(timeout=5000))
        except Exception:
            pass
        otp_code = get_sms(sms_page, current_number["index"], timeout=50)
    if not otp_code:
        print("[-] Timeout waiting for OTP SMS after resend. Discarding attempt.")
        return False
    print(f"[+] Received OTP: {otp_code}")
    inputs = page.locator("input[type='text'], input").all()
    if len(inputs) >= len(otp_code):
        for i, digit in enumerate(otp_code):
            execute_with_retry(lambda: inputs[i].click(timeout=50000))
            execute_with_retry(lambda: inputs[i].fill(digit, timeout=50000))
    else:
        execute_with_retry(lambda: otp_input.click(timeout=50000))
        execute_with_retry(lambda: otp_input.fill(otp_code, timeout=50000))
    print("[+] OTP entered successfully !")
    time.sleep(2)
    incorrect_otp_msg = page.get_by_text("The SMS passcode you've entered is incorrect.").first
    if incorrect_otp_msg.is_visible(timeout=5000):
        print("[-] Incorrect SMS passcode detected. Discarding attempt.")
        return False
    reached_name_page = False
    first_input_probe = page.locator("#FIRST_NAME, input[name='FIRST_NAME'], input[placeholder*='first' i]").first
    for _ in range(60):
        try:
            not_btn = page.locator(r"text=/Not .+\?/").first
            if not_btn.is_visible():
                print("[*] 'Not you?' detected. Clicking to reach name screen...")
                execute_with_retry(lambda: not_btn.click(timeout=5000))
                time.sleep(1)
        except Exception:
            pass
        try:
            reset_btn = page.locator("button:has-text('Reset Account')").first
            if reset_btn.is_visible():
                print("[*] 'Reset your account' screen detected. Clicking 'Reset Account'...")
                execute_with_retry(lambda: reset_btn.click(timeout=5000))
                time.sleep(1)
        except Exception:
            pass
        if first_input_probe.is_visible():
            reached_name_page = True
            break
        time.sleep(0.5)
    if not reached_name_page:
        print("[-] Could not reach name screen (unskippable account wall). Discarding attempt.")
        return False
    first_name = manual_name["first"] if manual_name else faker.first_name()
    last_name = manual_name["last"] if manual_name else faker.last_name()
    try:
        print("[*] Waiting for name screen to appear...")
        first_input = page.locator("#FIRST_NAME, input[name='FIRST_NAME'], input[placeholder*='first' i]").first
        execute_with_retry(lambda: first_input.wait_for(state="visible", timeout=50000))
        time.sleep(1)
        print("[*] Filling name...")
        execute_with_retry(lambda: first_input.click(force=True, timeout=50000))
        execute_with_retry(lambda: first_input.fill(first_name, timeout=50000))
        last_input = page.locator("#LAST_NAME, input[name='LAST_NAME'], input[placeholder*='last' i]").first
        execute_with_retry(lambda: last_input.wait_for(state="visible", timeout=50000))
        execute_with_retry(lambda: last_input.click(force=True, timeout=50000))
        execute_with_retry(lambda: last_input.fill(last_name, timeout=50000))
        time.sleep(0.5)
        next_btn = page.locator("button:has-text('Next')").first
        execute_with_retry(lambda: next_btn.wait_for(state="visible", timeout=50000))
        execute_with_retry(lambda: next_btn.click(force=True, timeout=50000))
        print("[+] Name submitted successfully.")
    except Exception as e:
        print(f"[-] Error handling name page: {e}")
        return False
    try:
        print("[*] Waiting for Terms & Privacy screen...")
        terms_checkbox = page.locator("#LEGAL_ACCEPT_TERMS, input[type='checkbox']").first
        execute_with_retry(lambda: terms_checkbox.wait_for(state="visible", timeout=50000))
        print("[*] Accepting terms and privacy notice...")
        execute_with_retry(lambda: terms_checkbox.click(timeout=50000))
        time.sleep(0.5)
        next_btn = page.locator("button:has-text('Next')").first
        execute_with_retry(lambda: next_btn.wait_for(state="visible", timeout=50000))
        execute_with_retry(lambda: next_btn.click(timeout=50000))
        print("[+] Terms accepted and clicked Next successfully.")
    except Exception as e:
        print(f"[-] Error handling terms page: {e}")
        return False
    time.sleep(5)
    print("[*] Navigating to m.uber.com to check for promotions...")
    promo_applied = "NO"
    try:
        execute_with_retry(lambda: page.goto("https://m.uber.com", wait_until="domcontentloaded", timeout=50000))
        get_ride_elem = page.locator("text='Get a ride'").first
        execute_with_retry(lambda: get_ride_elem.wait_for(state="visible", timeout=50000))
        print("[*] Opened Get a ride page")
        try:
            page.locator(f"text='{PROMOTION_TEXT}'").first.wait_for(state="visible", timeout=3000)
            promo_applied = "YES"
            print("[+] Promotion applied: YES")
        except Exception:
            print("[-] Promotion applied: NO")
    except Exception as e:
        print(f"[-] Could not verify promotion status (Timeout/Error). Defaulting to NO.")
    print("[*] Navigating current tab to Security for 2FA setup...")
    try:
        execute_with_retry(lambda: page.goto("https://account.uber.com/security", wait_until="networkidle", timeout=50000))
        authenticator_button = (
            page.get_by_role("button", name="Authenticator app")
            .or_(page.locator("text=Authenticator app"))
            .or_(page.locator("text=Set up 2-step verification"))
        ).first
        execute_with_retry(lambda: authenticator_button.wait_for(state="visible", timeout=50000))
        execute_with_retry(lambda: authenticator_button.click(timeout=50000))
    except Exception as e:
        print(f"[-] Could not find the Authenticator button on the Security page.")
        screenshot_path = os.path.join(SCRIPT_DIR, f"error_security_page_{current_serial}.png")
        page.screenshot(path=screenshot_path)
        print(f"[*] Saved error screenshot to {screenshot_path}")
        return False
    secret_key_regex = r"[A-Z2-7]{4}-[A-Z2-7]{4}-[A-Z2-7]{4}-[A-Z2-7]{4}-[A-Z2-7]{4}-[A-Z2-7]{4}-[A-Z2-7]{4}-[A-Z2-7]{4}"
    match = None
    for _ in range(15):
        try:
            page_source = page.content()
            match = re.search(secret_key_regex, page_source)
            if match:
                break
        except Exception:
            pass
        time.sleep(0.5)
    if not match:
        print("[-] Could not find the Secret Key on the page.")
        return False
    formatted_secret = match.group(0)
    secret_key = formatted_secret.replace("-", "")
    print(f"[+] Found Uber Secret Key: {secret_key}")
    totp = pyotp.TOTP(secret_key)
    current_code = totp.now()
    try:
        next_button = page.locator("//button[contains(text(), 'Next')]").first
        next_button.click(timeout=5000)
    except Exception:
        pass
    try:
        totp_input = page.locator("input[type='text'], input").first
        execute_with_retry(lambda: totp_input.wait_for(state="visible", timeout=50000))
        execute_with_retry(lambda: totp_input.fill(current_code, timeout=50000))
        page.keyboard.press("Enter")
    except Exception as e:
        print(f"[-] Failed to enter 2FA code: {e}")
        return False
    try:
        confirm_button = page.locator("//button[contains(text(), 'Next')]").first
        confirm_button.click(timeout=5000)
    except Exception:
        pass
    print("[*] Verifying 2FA setup success...")
    twofa_enabled = False
    for verify_attempt in range(3):
        print(f"[*] Checking 2FA status (Attempt {verify_attempt + 1}/3)...")
        try:
            indicator = (
                page.locator("text=Authenticator app enabled")
                .or_(page.locator("text=Remove"))
                .first
            )
            execute_with_retry(lambda: indicator.wait_for(state="visible", timeout=50000))
            twofa_enabled = True
            break
        except Exception:
            time.sleep(2)
    if not twofa_enabled:
        print("[-] Error: Could not verify if 2FA was enabled after 3 attempts.")
        return False
    print("[+] 2FA enabled successfully!")
    try:
        print("[*] Refreshing security tab to configure Password...")
        execute_with_retry(lambda: page.reload(wait_until="domcontentloaded", timeout=50000))
        time.sleep(2)
        print("[*] Clicking Password...")
        execute_with_retry(lambda: page.locator('[data-testid="security.info.password.label.btn"]').click(timeout=50000))
        print(f"[*] Entering new password: {PASSWORD_VALUE}...")
        new_pwd = page.locator('[data-testid="input.password.new"]')
        execute_with_retry(lambda: new_pwd.wait_for(state="visible", timeout=50000))
        execute_with_retry(lambda: new_pwd.fill(PASSWORD_VALUE, timeout=50000))
        confirm_pwd = page.locator('[data-testid="input.password.confirm"]')
        execute_with_retry(lambda: confirm_pwd.wait_for(state="visible", timeout=50000))
        execute_with_retry(lambda: confirm_pwd.fill(PASSWORD_VALUE, timeout=50000))
        time.sleep(1)
        print("[*] Submitting updated password...")
        update_btn = page.locator('[data-testid="enter-password-ui.navigation.next-button"]')
        execute_with_retry(lambda: update_btn.wait_for(state="visible", timeout=50000))
        execute_with_retry(lambda: update_btn.click(timeout=50000))
        print(f"[+] Password successfully updated to '{PASSWORD_VALUE}'!")
        time.sleep(3)
    except Exception as e:
        print(f"[-] Error during password setup: {e}")
        return False
    time.sleep(5)
    print("[*] Navigating to bonjour.uber.com for driver onboarding...")
    try:
        execute_with_retry(lambda: page.goto("https://bonjour.uber.com", wait_until="domcontentloaded", timeout=50000))
    except Exception:
        print("[-] Failed to load bonjour.uber.com.")
        return False
    gateway = "Unknown"
    try:
        if page.locator("text='Earn on your terms'").first.is_visible(timeout=10000):
            print("[*] 'Earn on your terms' detected. Clicking 'Join now'...")
            join_btn = page.locator("button:has-text('Join now')").first
            execute_with_retry(lambda: join_btn.click(timeout=50000))
            time.sleep(2)
    except Exception as e:
        print(f"[-] Error on 'Earn on your terms': {e}")
    earn_handled = False
    try:
        if page.locator("text='Choose how you want to earn with Uber'").first.is_visible(timeout=10000) and not earn_handled:
            print("[*] Selecting 'Commercial car'...")
            commercial_opt = page.locator("text='Commercial car'").first
            execute_with_retry(lambda: commercial_opt.click(timeout=50000))
            time.sleep(1.5)
            try:
                cont_btn = page.locator("button:has-text('Continue')").first
                execute_with_retry(lambda: cont_btn.wait_for(state="visible", timeout=10000))
                execute_with_retry(lambda: cont_btn.click(timeout=10000))
            except Exception as e:
                print(f"[-] Error clicking generic continue: {e}")
            earn_handled = True
            time.sleep(2)
    except Exception as e:
        print(f"[-] Error selecting commercial car: {e}")
    language_handled = False
    try:
        lang_heading = page.locator("text='Select your language'").first
        dropdown = page.locator('[data-baseweb="select"]').first
        if (lang_heading.is_visible(timeout=10000) or dropdown.is_visible(timeout=10000)) and not language_handled:
            print("[*] Selecting 'English' language...")
            language_handled = True
            try:
                execute_with_retry(lambda: dropdown.wait_for(state="visible", timeout=10000))
                execute_with_retry(lambda: dropdown.click(timeout=10000))
                english_opt = page.get_by_text("English", exact=True).first
                execute_with_retry(lambda: english_opt.wait_for(state="visible", timeout=10000))
                execute_with_retry(lambda: english_opt.click(timeout=10000))
            except Exception as lang_error:
                print(f"[!] Warning: Language select error: {lang_error}")
                language_handled = False
            time.sleep(1.5)
            try:
                cont_btn = page.locator("button:has-text('Continue')").first
                execute_with_retry(lambda: cont_btn.wait_for(state="visible", timeout=10000))
                execute_with_retry(lambda: cont_btn.click(timeout=10000))
            except Exception as e:
                print(f"[-] Error clicking generic continue: {e}")
            time.sleep(2)
    except Exception as e:
        print(f"[!] Warning: Language select error: {e}")
        language_handled = False
    try:
        cont_btn = page.locator("button:has-text('Continue')").first
        if cont_btn.is_visible(timeout=5000):
            if page.locator("text='Select your language'").first.is_visible(timeout=5000) and not language_handled:
                pass
            else:
                print("[*] Clicking additional 'Continue'...")
                execute_with_retry(lambda: cont_btn.click(timeout=5000))
                time.sleep(1.5)
    except Exception:
        pass
    print("[*] Waiting for Welcome page / Profile Picture step...")
    try:
        profile_photo_row = page.get_by_text(re.compile(r"Profile (Photo|Picture)", re.IGNORECASE)).last
        execute_with_retry(lambda: profile_photo_row.wait_for(state="visible", timeout=50000))
        execute_with_retry(lambda: profile_photo_row.scroll_into_view_if_needed())
        execute_with_retry(lambda: profile_photo_row.click(timeout=50000))
    except Exception:
        try:
            js_code = "() => { const elements = Array.from(document.querySelectorAll('*')).filter(el => { const text = el.textContent.trim(); return /Profile (Photo|Picture)/i.test(text) && el.offsetWidth > 0; }); let bestMatch = elements.find(el => el.tagName === 'BUTTON' || el.getAttribute('role') === 'button' || window.getComputedStyle(el).cursor === 'pointer'); if (!bestMatch && elements.length > 0) { bestMatch = elements[elements.length - 1]; } if (bestMatch) { bestMatch.click(); } }"
            page.evaluate(js_code)
        except Exception as e:
            print(f"[-] JS evaluation for Profile Photo failed: {e}")
    time.sleep(2)
    try:
        take_photo = page.get_by_text(re.compile(r"Take( a)? photo", re.IGNORECASE)).first
        execute_with_retry(lambda: take_photo.wait_for(state="visible", timeout=50000))
        execute_with_retry(lambda: take_photo.scroll_into_view_if_needed())
        execute_with_retry(lambda: take_photo.click(timeout=50000))
    except Exception:
        try:
            page.locator("text='Take photo'").first.evaluate("el => el.click()")
        except Exception as e:
            print(f"[-] Critical: Could not click Take photo: {e}")
    print("[*] Checking for photo verification gateway...")
    try:
        page.wait_for_url(
            lambda url: "veriff" in url or "socure" in url,
            timeout=50000
        )
    except Exception:
        pass
    current_url = page.url
    time.sleep(2)
    success_status = True
    if "veriff" in current_url:
        print("[-] Veriff gateway detected. Discarding this attempt !")
        gateway = "Veriff"
        success_status = False
    elif "socure" in current_url:
        print("[+] Socure gateway detected.")
        gateway = "Socure"
        success_status = True
    else:
        print(f"[-] Unrecognized endpoint: {current_url}")
        gateway = "Unknown"
        success_status = False
    save_account(
        current_serial,
        full_name,
        phone_number,
        secret_key,
        promo_applied,
        gateway
    )
    return success_status

if __name__ == "__main__":
    if "-h" in sys.argv or "--help" in sys.argv:
        print(f"Usage: python {os.path.basename(__file__)} [no. of accounts] [First name] [Last name]")
        sys.exit(0)
    total_accounts = 1
    manual_name = None
    if len(sys.argv) > 1:
        try:
            total_accounts = int(sys.argv[1])
        except ValueError:
            total_accounts = 1
    if len(sys.argv) >= 4:
        manual_name = {"first": sys.argv[2], "last": sys.argv[3]}
    elif len(sys.argv) == 3:
        manual_name = {"first": sys.argv[2], "last": ""}
    initialize_output_file()
    successes = 0
    attempts = 0
    start_serial = get_next_serial()
    faker = Faker("en_IN")
    print(f"Welcome to Uber Batch Generator 😈\nNeed to successfully create {total_accounts} account(s).\n\nScript made by @ayusc (Git)")
    with Camoufox(headless=HEADLESS) as browser:
        sms_page = browser.new_page()
        setup_sms_panel(sms_page)
        uber_page = browser.new_page()
        current_row_index = 0
        numbers_to_cancel = []
        try:
            while successes < total_accounts:
                current_number = get_single_number(sms_page, current_row_index)
                if not current_number:
                    print(f"[-] Failed to get a number from row {current_row_index + 1}. Moving to next row...")
                    current_row_index = (current_row_index + 1) % 5
                    time.sleep(2)
                    continue
                attempts += 1
                current_serial = start_serial + successes
                print(f"\n======================================")
                print(f"[*] Starting Attempt {attempts} using {current_number['phone']} (Successful: {successes}/{total_accounts})")
                print(f"======================================")
                success = run_automation(
                    faker,
                    current_serial,
                    sms_page,
                    uber_page,
                    current_number,
                    manual_name,
                )
                if success:
                    successes += 1
                    print(f"[+] Account saved successfully! Progress: {successes}/{total_accounts}")
                else:
                    print(f"[-] Automation failed for {current_number['phone']}.")
                    numbers_to_cancel.append(current_number)
                current_row_index = (current_row_index + 1) % 5
                time.sleep(2)
        finally:
            if numbers_to_cancel:
                print("\nSome failed/unused numbers are yet to be cancelled. PLEASE DO NOT FORCE STOP THE SCRIPT OR CLOSE THE TERMINAL !")
                for unused_number in numbers_to_cancel:
                    cancel_number(sms_page, unused_number)
                print("\n[+] All excess numbers cleaned up.")
    print("\nSCRIPT COMPLETE.")
