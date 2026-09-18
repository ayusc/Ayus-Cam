# Script originally made by Ayus Chatterjee (git:@ayusc)
# Modified to utilize Camoufox anti-detect engine for better stealth.

import os
import sys
import time
import threading
import requests
import re
import pyotp
from faker import Faker
from camoufox.sync_api import Camoufox

API_KEY = "500ca544ac8096058e567e7c43a0c5e0"  # change if needed
SERVICE = "ub"
COUNTRY = "22"
MAXPRICE = "0.095"  # Only buys if price is <= $0.095
URL = "https://m.uber.com/go/login-redirect"
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
OUTPUT_FILE = os.path.join(SCRIPT_DIR, "uber_accounts.txt")
PASSWORD_VALUE = "uber5555"
HEADLESS = False  # Set to True for NON-GUI, False for GUI


def initialize_output_file():
    if not os.path.exists(OUTPUT_FILE):
        with open(OUTPUT_FILE, "w", encoding="utf-8") as f:
            f.write("Generated Uber Accounts:\n\n")
            header = "{:<15}{:<24}{:<24}{}\n".format(
                "Serial No.", "Name", "Phone", "Authentication-Key"
            )
            f.write(header)
            f.write("=" * 95 + "\n\n")


def save_account(serial, name, phone, secret_key):
    row = "{:<15}{:<24}{:<24}{}\n".format(str(serial), name, phone, secret_key)
    with open(OUTPUT_FILE, "a", encoding="utf-8") as f:
        f.write(row)


def get_number(retries=15):
    for attempt in range(retries):
        params = {
            "api_key": API_KEY,
            "action": "getNumberV2",
            "service": SERVICE,
            "country": COUNTRY,
            "maxPrice": MAXPRICE,
        }
        try:
            response = requests.get(
                "https://api.grizzlysms.com/stubs/handler_api.php",
                params=params,
                timeout=10,
            )
            res_text = response.text.strip()
        except Exception as e:
            print(f"[-] Network error: {e}")
            time.sleep(3)
            continue

        if res_text == "NO_NUMBERS":
            print(f"[-] Numbers Out of stock for country code {COUNTRY}")
        elif res_text in ("NO_BALANCE", "BAD_KEY"):
            print(f"[-] API error: {res_text}")
            return None, None
        else:
            try:
                data = response.json()
                if "activationId" in data and "phoneNumber" in data:
                    activation_id = data["activationId"]
                    raw_phone = str(data["phoneNumber"]).strip().lstrip("+")
                    local_phone = (
                        raw_phone[2:] if raw_phone.startswith("91") else raw_phone
                    )

                    if len(local_phone) != 10 or not local_phone.isdigit():
                        print(
                            f"[-] Invalid length ({len(local_phone)} digits): {raw_phone}. Canceling..."
                        )
                        threading.Thread(
                            target=delayed_cancel,
                            args=(activation_id, raw_phone),
                            daemon=False,
                        ).start()
                        time.sleep(2)
                        continue

                    formatted_phone = f"+91{local_phone}"
                    print(
                        f"[+] Successfully rented number: {formatted_phone} (Activation ID: {activation_id})"
                    )
                    return activation_id, formatted_phone

            except Exception as e:
                print(f"[-] Error parsing response: {e}, text: {res_text}")

        if attempt < retries - 1:
            time.sleep(3)

    return None, None


def delayed_cancel(activation_id, phone_number):
    print(
        f"Failed to proceed with this number !\nWaiting 120 seconds to send cancel request"
    )
    start_time = time.time()
    while time.time() - start_time < 120:
        time.sleep(10)
    cancel_number(activation_id)
    print(f"{phone_number} canceled successfully !")


def cancel_number(activation_id):
    params = {
        "api_key": API_KEY,
        "action": "setStatus",
        "status": "8",
        "id": str(activation_id),
    }
    try:
        response = requests.get(
            "https://api.grizzlysms.com/stubs/handler_api.php", params=params
        )
        print(f"[*] Cancel status response: {response.text}")
        return response.text
    except Exception:
        return None


def get_sms(activation_id, timeout=15):
    start_time = time.time()
    print("[*] Waiting for OTP ...")
    while time.time() - start_time < timeout:
        params = {"api_key": API_KEY, "action": "getStatus", "id": activation_id}
        response = requests.get(
            "https://api.grizzlysms.com/stubs/handler_api.php", params=params
        )
        res_text = response.text.strip()
        print(f"[*] SMS Status response: {res_text}")

        if "STATUS_OK" in res_text:
            if ":" in res_text:
                return res_text.split(":")[-1]
            try:
                data = response.json()
                if "code" in data:
                    return data["code"]
            except Exception:
                pass
        elif "STATUS_WAIT_CODE" in res_text:
            time.sleep(5)
            continue
        else:
            time.sleep(5)
    return None


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


def run_automation(faker, current_serial, manual_name=None):
    with Camoufox(
        headless=HEADLESS
    ) as browser:
        
        page = browser.new_page()

        print(f"[*] Navigating to {URL}")
        page.goto(URL, wait_until="domcontentloaded", timeout=30000)

        while True:
            activation_id, phone_number = get_number()
            if not activation_id or not phone_number:
                print("[-] Could not retrieve number. Retrying...")
                time.sleep(3)
                continue
            break

        while True:
            phone_input = page.locator(
                "#PHONE_NUMBER_or_EMAIL_ADDRESS, input[type='tel'], input[name='email'], input[placeholder*='phone' i]"
            ).first
            phone_input.wait_for(state="visible", timeout=20000)
            phone_input.click()

            phone_input.fill(phone_number)
            time.sleep(1)

            continue_btn = page.locator("button:has-text('Continue')").first
            continue_btn.click()
            print("[+] Clicked Continue. Waiting for OTP screen...")

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

            welcome_back_stuck = False
            for _ in range(25):
                not_btn = page.locator(r"text=/Not .+\?/").first
                if not_btn.is_visible():
                    print("[*] 'Not you?' detected before OTP. Clicking...")
                    not_btn.click()
                    time.sleep(1)

                if page.locator("text=Welcome back").first.is_visible():
                    send_sms_btn = page.get_by_text(
                        "Send code via SMS", exact=True
                    ).first
                    if send_sms_btn.is_visible():
                        print(
                            "[*] 'Welcome back' screen detected. Clicking 'Send code via SMS'..."
                        )
                        send_sms_btn.click()
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
                print(
                    "[-] 'Welcome back' without 'Not you?' or SMS option. Marking as locked..."
                )
                is_locked = True

            if is_locked:
                print(
                    f"[-] Phone {phone_number} is blocked or locked. Cancelling number..."
                )
                threading.Thread(
                    target=delayed_cancel,
                    args=(activation_id, phone_number),
                    daemon=False,
                ).start()

                print("[*] Reloading login page and fetching a fresh number...")
                page.goto(URL, wait_until="domcontentloaded", timeout=30000)
                time.sleep(2)

                while True:
                    activation_id, phone_number = get_number()
                    if not activation_id or not phone_number:
                        time.sleep(3)
                        continue
                    break
                continue

            otp_input = page.locator(
                "input[type='text'], input[maxlength='1'], input"
            ).first
            otp_input.wait_for(state="visible", timeout=50000)

            otp_code = get_sms(activation_id, timeout=30)

            if page.locator("text=Bad Request").first.is_visible():
                print("[-] 'Bad Request' popup detected on OTP screen. Canceling number...")
                threading.Thread(
                    target=delayed_cancel,
                    args=(activation_id, phone_number),
                    daemon=False,
                ).start()
                
                print("[*] Reloading login page and fetching a fresh number...")
                page.goto(URL, wait_until="domcontentloaded", timeout=30000)
                time.sleep(2)              
              
                while True:
                    activation_id, phone_number = get_number()
                    if activation_id and phone_number:
                        break
                    time.sleep(2)
                continue

            if not otp_code:
                print(
                    "[-] Timeout waiting for initial OTP. Clicking 'Resend code via SMS'..."
                )
                resend_btn = page.locator("#alt-action-resend-sms").first
                try:
                    resend_btn.wait_for(state="visible", timeout=10000)
                    resend_btn.click()
                except Exception:
                    pass

                time.sleep(1)
                retry_btn = page.locator("button:has-text('Retry')").first
                try:
                    if retry_btn.is_visible(timeout=3000):
                        print("[*] Error popup detected. Clicking 'Retry'...")
                        retry_btn.click()
                except Exception:
                    pass

                otp_code = get_sms(activation_id, timeout=30)

            if not otp_code:
                print(
                    "[-] Timeout waiting for OTP SMS after resend. Canceling number and trying a new one..."
                )
                threading.Thread(
                    target=delayed_cancel,
                    args=(activation_id, phone_number),
                    daemon=False,
                ).start()
                page.goto(URL, wait_until="domcontentloaded", timeout=30000)
                time.sleep(2)
                while True:
                    activation_id, phone_number = get_number()
                    if activation_id and phone_number:
                        break
                    time.sleep(2)
                continue
            
            print(f"[+] Received OTP: {otp_code}")

            inputs = page.locator("input[type='text'], input").all()
            if len(inputs) >= len(otp_code):
                for i, digit in enumerate(otp_code):
                    inputs[i].click()
                    inputs[i].fill(digit)
            else:
                otp_input.click()
                otp_input.fill(otp_code)

            print("[+] OTP entered successfully !")

            reached_name_page = False
            first_input_probe = page.locator(
                "#FIRST_NAME, input[name='FIRST_NAME'], input[placeholder*='first' i]"
            ).first

            for _ in range(20):
                try:
                    not_btn = page.locator(r"text=/Not .+\?/").first
                    if not_btn.is_visible():
                        print(
                            "[*] 'Not you?' detected. Clicking to reach name screen..."
                        )
                        not_btn.click(timeout=3000)
                        time.sleep(1)
                except Exception:
                    pass

                try:
                    reset_btn = page.locator("button:has-text('Reset Account')").first
                    if reset_btn.is_visible():
                        print(
                            "[*] 'Reset your account' screen detected. Clicking 'Reset Account'..."
                        )
                        reset_btn.click(timeout=3000)
                        time.sleep(1)
                except Exception:
                    pass

                if first_input_probe.is_visible():
                    reached_name_page = True
                    break

                time.sleep(0.5)

            if not reached_name_page:
                print(
                    "[-] Could not reach name screen (unskippable account). Canceling number and retrying..."
                )
                threading.Thread(
                    target=delayed_cancel,
                    args=(activation_id, phone_number),
                    daemon=False,
                ).start()
                page.goto(URL, wait_until="domcontentloaded", timeout=30000)
                time.sleep(2)
                while True:
                    activation_id, phone_number = get_number()
                    if activation_id and phone_number:
                        break
                    time.sleep(2)
                continue

            break

        first_name = manual_name["first"] if manual_name else faker.first_name()
        last_name = manual_name["last"] if manual_name else faker.last_name()

        try:
            print("[*] Waiting for name screen to appear...")
            first_input = page.locator(
                "#FIRST_NAME, input[name='FIRST_NAME'], input[placeholder*='first' i]"
            ).first
            first_input.wait_for(state="visible", timeout=15000)

            print("[*] Filling name...")
            first_input.click()
            first_input.fill(first_name)

            last_input = page.locator(
                "#LAST_NAME, input[name='LAST_NAME'], input[placeholder*='last' i]"
            ).first
            last_input.wait_for(state="visible", timeout=5000)
            last_input.click()
            last_input.fill(last_name)

            time.sleep(0.5)
            next_btn = page.locator("button:has-text('Next')").first
            next_btn.wait_for(state="visible", timeout=5000)
            next_btn.click()
            print("[+] Name submitted successfully.")
        except Exception as e:
            print(f"[-] Error handling name page: {e}")
            return False

        try:
            print("[*] Waiting for Terms & Privacy screen...")
            terms_checkbox = page.locator(
                "#LEGAL_ACCEPT_TERMS, input[type='checkbox']"
            ).first
            terms_checkbox.wait_for(state="visible", timeout=15000)

            print("[*] Accepting terms and privacy notice...")
            terms_checkbox.click()
            time.sleep(0.5)

            next_btn = page.locator("button:has-text('Next')").first
            next_btn.wait_for(state="visible", timeout=5000)
            next_btn.click()
            print("[+] Terms accepted and clicked Next successfully.")
        except Exception as e:
            print(f"[-] Error handling terms page: {e}")
            return False

        time.sleep(8)

        # ----------------------------------------------------
        # UPDATED SECURITY PAGE BLOCK WITH CRASH PREVENTION
        # ----------------------------------------------------
        print("[*] Opening Security tab for 2FA setup...")
        new_page = page.context.new_page()
        
        try:
            new_page.goto("https://account.uber.com/security", wait_until="networkidle", timeout=30000)
            
            authenticator_button = (
                new_page.get_by_role("button", name="Authenticator app")
                .or_(new_page.locator("text=Authenticator app"))
                .or_(new_page.locator("text=Set up 2-step verification"))
            ).first
            
            authenticator_button.wait_for(state="visible", timeout=15000)
            authenticator_button.click()
            
        except Exception as e:
            print(f"[-] Could not find the Authenticator button on the Security page.")
            screenshot_path = os.path.join(SCRIPT_DIR, f"error_security_page_{current_serial}.png")
            new_page.screenshot(path=screenshot_path)
            print(f"[*] Saved error screenshot to {screenshot_path}")
            return False

        secret_key_regex = r"[A-Z2-7]{4}-[A-Z2-7]{4}-[A-Z2-7]{4}-[A-Z2-7]{4}-[A-Z2-7]{4}-[A-Z2-7]{4}-[A-Z2-7]{4}-[A-Z2-7]{4}"
        match = None

        for _ in range(15):
            try:
                page_source = new_page.content()
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

        full_name = " ".join(filter(None, [first_name, last_name]))
        phone_with_code = phone_number

        save_account(
            current_serial,
            full_name,
            phone_with_code,
            secret_key,
        )

        totp = pyotp.TOTP(secret_key)
        current_code = totp.now()

        next_button = new_page.locator("//button[contains(text(), 'Next')]").first
        next_button.click(timeout=3000)

        totp_input = new_page.locator("input[type='text'], input").first
        totp_input.wait_for(state="visible", timeout=8000)
        totp_input.fill(current_code)

        new_page.keyboard.press("Enter")

        try:
            confirm_button = new_page.locator(
                "//button[contains(text(), 'Next')]"
            ).first
            confirm_button.click(timeout=1500)
        except Exception:
            pass

        print("[*] Verifying 2FA setup success...")
        twofa_enabled = False

        for verify_attempt in range(3):
            print(f"[*] Checking 2FA status (Attempt {verify_attempt + 1}/3)...")
            try:
                indicator = (
                    new_page.locator("text=Authenticator app enabled")
                    .or_(new_page.locator("text=Remove"))
                    .first
                )
                indicator.wait_for(state="visible", timeout=10000)
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
            new_page.reload(wait_until="domcontentloaded", timeout=50000)
            time.sleep(2)

            print("[*] Clicking Password...")
            new_page.locator('[data-testid="security.info.password.label.btn"]').click()

            print(f"[*] Entering new password: {PASSWORD_VALUE}...")
            new_pwd = new_page.locator('[data-testid="input.password.new"]')
            new_pwd.wait_for(state="visible", timeout=50000)
            new_pwd.fill(PASSWORD_VALUE)

            confirm_pwd = new_page.locator('[data-testid="input.password.confirm"]')
            confirm_pwd.wait_for(state="visible", timeout=50000)
            confirm_pwd.fill(PASSWORD_VALUE)

            time.sleep(1)

            print("[*] Submitting updated password...")
            update_btn = new_page.locator(
                '[data-testid="enter-password-ui.navigation.next-button"]'
            )
            update_btn.wait_for(state="visible", timeout=50000)
            update_btn.click()

            print(f"[+] Password successfully updated to '{PASSWORD_VALUE}'!")
            time.sleep(3)
        except Exception as e:
            print(f"[-] Error during password setup: {e}")
            return False

        return True


if __name__ == "__main__":
    if "-h" in sys.argv or "--help" in sys.argv:
        print(
            f"Usage: python {os.path.basename(__file__)} [no. of accounts] [First name] [Last name]"
        )
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

    print(
        f"Welcome to Uber Batch Generator 😈\n"
        f"Need to successfully create {total_accounts} account(s).\n\n"
        f"Script made by @ayusc (Git)"
    )

    while successes < total_accounts:
        attempts += 1
        current_serial = start_serial + successes
        print(
            f"\n[*] Starting Attempt {attempts} (Successful: {successes}/{total_accounts})"
        )

        success = run_automation(faker, current_serial, manual_name)

        if success:
            successes += 1
            print(
                f"[+] Account saved successfully! Progress: {successes}/{total_accounts}"
            )
        else:
            print("[-] Attempt failed. Retrying...")
            time.sleep(3)

    print("\nSCRIPT COMPLETE.")
