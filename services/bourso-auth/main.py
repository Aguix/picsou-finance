"""
BoursoBank Auth & Sync Sidecar
------------------------------
Handles BoursoBank authentication (virtual keyboard challenge + optional MFA)
and account/position/transaction data fetching.

Auth flow:
  POST /initiate  {customerId, password}
    → no MFA:  {processId, mfaRequired: false, sessionCookies: "..."}
    → MFA:     {processId, mfaRequired: true, mfaType, contact}

  POST /complete  {processId, code}  (only when mfaRequired)
    → {sessionCookies: "..."}

  POST /accounts  {sessionCookies: "..."}
    → [{id, name, type, balance, positions: [...], transactions: [...]}]

Session state is stored in memory only during the auth flow (keyed by processId).
After auth completes, serialized cookies are returned to Java for encrypted storage.
"""

import asyncio
import csv
import io
import json
import logging
import re
import time
import uuid
from datetime import date, timedelta
from typing import Optional

import httpx
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

logging.basicConfig(level=logging.INFO)
log = logging.getLogger("bourso-auth")

app = FastAPI()

# BoursoBank endpoints
BOURSO_BASE = "https://clients.boursobank.com"
BOURSO_API  = "https://api.boursobank.com/services/api/v1.7"

# In-memory auth state: processId → {client, user_hash, mfa_state}
# Cleaned up after /complete or after TTL
_pending: dict[str, dict] = {}
_PENDING_TTL = 600  # 10 minutes

_USER_AGENT = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
)

# ─── Helpers ──────────────────────────────────────────────────────────────────

def _base_headers() -> dict:
    return {
        "User-Agent": _USER_AGENT,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "fr-FR,fr;q=0.9,en;q=0.8",
    }


def _make_client(cookies: dict | None = None) -> httpx.AsyncClient:
    return httpx.AsyncClient(
        base_url=BOURSO_BASE,
        follow_redirects=True,
        timeout=httpx.Timeout(30.0),
        headers=_base_headers(),
        cookies=cookies or {},
    )


def _extract_form_token(html: str) -> Optional[str]:
    """Extract _token hidden input value from a BoursoBank (Symfony) form.

    Symfony renders the CSRF field as name="form[_token]" (with brackets)
    and id="form__token". We try several patterns in order of specificity.
    """
    # 1. Symfony: name="form[_token]"  (most common)
    m = re.search(r'(<input[^>]+name="form\[_token\]"[^>]*>)', html)
    # 2. Plain: name="_token"
    if not m:
        m = re.search(r'(<input[^>]+name="_token"[^>]*>)', html)
    # 3. Symfony id convention: id="form__token"
    if not m:
        m = re.search(r'(<input[^>]+id="form__token"[^>]*>)', html)
    # 4. Any hidden input whose name contains "_token"
    if not m:
        m = re.search(r'(<input[^>]+name="[^"]*_token[^"]*"[^>]*>)', html)
    if not m:
        return None
    tag = m.group(1)
    v = re.search(r'value="([^"]*)"', tag)
    return v.group(1) if v else None


def _extract_challenge(html: str) -> str:
    """Extract matrixRandomChallenge from login page or keyboard fragment."""
    # Attribute on the keyboard container: data-matrix-random-challenge="..."
    m = re.search(r'data-matrix-random-challenge="([^"]+)"', html)
    if m:
        return m.group(1)
    # Hidden input: name="form[matrixRandomChallenge]" or id containing challenge
    m = re.search(r'<input[^>]+name="form\[matrixRandomChallenge\]"[^>]*>', html)
    if m:
        v = re.search(r'value="([^"]*)"', m.group(0))
        if v:
            return v.group(1)
    return ""


def _extract_user_hash(html: str) -> Optional[str]:
    """Extract USER_HASH from window.BRS_CONFIG embedded in page HTML."""
    m = re.search(r'"USER_HASH"\s*:\s*"([^"]+)"', html)
    if not m:
        m = re.search(r"USER_HASH['\"]?\s*:\s*['\"]([^'\"]+)['\"]", html)
    return m.group(1) if m else None


def _parse_vpad(html: str) -> dict[str, str]:
    """
    Build a digit → key-code mapping from the virtual keyboard HTML.

    BoursoBank renders 10 shuffled buttons:
        <button ... data-matrix-key="XYZ" ...>5</button>
    The button text is the digit; data-matrix-key is what gets submitted.
    """
    digit_map: dict[str, str] = {}
    for m in re.finditer(
        r'<button[^>]+data-matrix-key="([A-Z]+)"[^>]*>\s*(\d)\s*</button>',
        html,
    ):
        digit_map[m.group(2)] = m.group(1)

    if not digit_map:
        # Fallback: assume DOM order is 0-9 (older keyboard format)
        keys = re.findall(r'data-matrix-key="([A-Z]+)"', html)
        digit_map = {str(i): k for i, k in enumerate(keys)}

    return digit_map


def _encode_password(password: str, digit_map: dict[str, str]) -> str:
    """Encode password using the digit→key map from the virtual keyboard."""
    return "|".join(digit_map[ch] for ch in password if ch in digit_map)


def _serialize_cookies(client: httpx.AsyncClient) -> str:
    """Serialize client cookies to a JSON string for storage in Java."""
    cookies = {}
    for cookie in client.cookies.jar:
        cookies[cookie.name] = cookie.value
    return json.dumps(cookies)


def _account_type(name_lower: str, kind: str) -> str:
    """Map BoursoBank account name/kind to Picsou AccountType."""
    if "pea" in name_lower:
        return "PEA"
    if "compte titre" in name_lower or "cto" in name_lower or "titres" in name_lower:
        return "COMPTE_TITRES"
    if "lep" in name_lower:
        return "LEP"
    if "livret" in name_lower or "savings" in kind.lower():
        return "SAVINGS"
    if kind.lower() in ("savings",):
        return "SAVINGS"
    if kind.lower() in ("loans",):
        return "LOAN"
    return "CHECKING"


def _clean_pending():
    """Remove expired pending sessions."""
    now = time.time()
    expired = [k for k, v in _pending.items() if now - v.get("created_at", 0) > _PENDING_TTL]
    for k in expired:
        try:
            asyncio.get_event_loop().create_task(_pending.pop(k)["client"].aclose())
        except Exception:
            _pending.pop(k, None)


# ─── Auth flow ────────────────────────────────────────────────────────────────

async def _resolve_js_cookie_challenge(client: httpx.AsyncClient, resp: httpx.Response) -> httpx.Response:
    """
    BoursoBank serves a JS-only page before the real login page that sets __brs_mit
    via document.cookie and calls window.location.reload(). httpx doesn't execute JS,
    so we detect this page, extract the cookie value from the script, set it manually,
    then re-fetch the original URL.
    """
    m = re.search(r'document\.cookie\s*=\s*"(__brs_mit=([^;]+));', resp.text)
    if not m:
        return resp  # Not a challenge page — already the real page

    cookie_value = m.group(2)
    log.info("BoursoBank JS cookie challenge detected — setting __brs_mit manually")
    client.cookies.set("__brs_mit", cookie_value, domain="clients.boursobank.com")

    real_resp = await client.get("/connexion/")
    real_resp.raise_for_status()
    return real_resp


async def _initiate_auth(customer_id: str, password: str) -> dict:
    """
    Executes the BoursoBank login flow up to the MFA decision point.

    Returns a dict with:
      - client: the authenticated httpx.AsyncClient (cookie jar intact)
      - user_hash: extracted USER_HASH (may be None before MFA)
      - mfa_required: bool
      - mfa_type: "EMAIL" | "SMS" | "APP" | None
      - mfa_contact: partial contact info (e.g., "j**@ex**.com")
      - mfa_state: {otp_id, form_state, token, user_hash} (for MFA completion)
    """
    client = _make_client()

    # Step 1: GET /connexion/ — may return a JS cookie challenge first
    resp = await client.get("/connexion/")
    resp.raise_for_status()
    resp = await _resolve_js_cookie_challenge(client, resp)

    login_html = resp.text
    form_token = _extract_form_token(login_html)
    if not form_token:
        snippet = login_html[:500].replace("\n", " ")
        token_ctx = ""
        ti = login_html.find("_token")
        if ti >= 0:
            token_ctx = " | _token context: " + login_html[max(0, ti-100):ti+200].replace("\n", " ")
        log.warning("Could not find _token in login page. Head: %s%s", snippet, token_ctx)
        await client.aclose()
        raise HTTPException(status_code=502, detail="Could not extract form token from BoursoBank login page")

    # Challenge lives on the main login page, not the keyboard fragment
    challenge = _extract_challenge(login_html)

    # Step 2: GET virtual keyboard (key-code mapping only)
    vpad_resp = await client.get(
        "/connexion/clavier-virtuel",
        params={"_hinclude": "1"},
        headers={"X-Requested-With": "XMLHttpRequest", "Accept": "application/json, text/javascript, */*"},
    )
    vpad_resp.raise_for_status()

    vpad_html = vpad_resp.text
    # Also check keyboard fragment in case challenge moved there
    if not challenge:
        challenge = _extract_challenge(vpad_html)

    digit_map = _parse_vpad(vpad_html)

    if len(digit_map) < 10:
        # Fallback: JSON response format
        try:
            vpad_json = vpad_resp.json()
            pad_keys_raw = vpad_json.get("keyPadContent", [])
            if pad_keys_raw:
                digit_map = {str(i): k.get("id", "") for i, k in enumerate(pad_keys_raw)}
                if not challenge:
                    challenge = vpad_json.get("matrixRandomChallenge", "")
        except Exception:
            pass

    if len(digit_map) < 10:
        await client.aclose()
        raise HTTPException(status_code=502, detail=f"Virtual keyboard parsing failed: got {len(digit_map)} keys")

    encoded_pwd = _encode_password(password, digit_map)
    log.info("Virtual keyboard: %d keys mapped, encoded %d password digits, challenge=%s",
             len(digit_map), len(encoded_pwd.split("|")), challenge[:8] if challenge else "MISSING")

    # Step 3: POST credentials
    login_resp = await client.post(
        "/connexion/saisie-mot-de-passe",
        data={
            "form[_token]": form_token,
            "form[clientNumber]": customer_id,
            "form[password]": encoded_pwd,
            "form[matrixRandomChallenge]": challenge,
            "form[fakePassword]": "••••••••",
            "form[ajx]": "1",
            "form[passwordAck]": '{"ry":[],"pt":[],"js":true}',
            "form[platformAuthenticatorAvailable]": "1",
        },
        headers={"Content-Type": "application/x-www-form-urlencoded"},
    )

    final_html = login_resp.text
    final_url  = str(login_resp.url)

    log.info("Login POST → url=%s status=%d", final_url, login_resp.status_code)
    log.debug("Login POST response snippet: %s", final_html[:1000].replace("\n", " "))

    # MFA required?
    if "securisation" in final_url or "securisation" in final_html:
        return await _parse_mfa_page(client, final_html, final_url)

    # Check for successful login
    if "se-deconnecter" in final_html:
        user_hash = _extract_user_hash(final_html)
        return {"client": client, "user_hash": user_hash, "mfa_required": False}

    # Bad credentials — log a snippet to see the actual error message
    log.warning("Login failed. URL=%s snippet=%s", final_url, final_html[:2000].replace("\n", " "))
    await client.aclose()
    if "identifiant" in final_html.lower() or "mot de passe" in final_html.lower() or "erreur" in final_html.lower():
        raise HTTPException(status_code=401, detail="Invalid BoursoBank credentials")
    raise HTTPException(status_code=502, detail="Unexpected response from BoursoBank login")


async def _parse_mfa_page(client: httpx.AsyncClient, html: str, url: str) -> dict:
    """Parse the MFA securisation page to extract challenge parameters."""
    # Fetch /securisation/validation if not already there
    if "validation" not in url:
        resp = await client.get("/securisation/validation")
        resp.raise_for_status()
        html = resp.text

    user_hash = _extract_user_hash(html)

    # Extract strong authentication payload from data attribute
    m = re.search(r'data-strong-authentication-payload="([^"]+)"', html)
    if not m:
        await client.aclose()
        raise HTTPException(status_code=502, detail="Could not find MFA payload in BoursoBank securisation page")

    payload_raw = m.group(1).replace("&quot;", '"')
    try:
        payload = json.loads(payload_raw)
    except json.JSONDecodeError:
        await client.aclose()
        raise HTTPException(status_code=502, detail="Could not parse BoursoBank MFA payload")

    # Navigate the payload to find otp_id, form_state, check/start paths
    # Expected: payload["challenges"][0]["parameters"]["formScreen"]["actions"]
    try:
        challenge_params = payload["challenges"][0]["parameters"]["formScreen"]
        actions = challenge_params["actions"]
        check_action = actions.get("check", {})
        start_action = actions.get("start", {})
        form_state = challenge_params.get("formState", "")
        otp_id = check_action.get("api", {}).get("params", {}).get("id", "")
        if not otp_id:
            otp_id = start_action.get("api", {}).get("params", {}).get("id", "")
        check_path = check_action.get("api", {}).get("path", "")
        start_path = start_action.get("api", {}).get("path", "")
    except (KeyError, IndexError, TypeError) as e:
        await client.aclose()
        raise HTTPException(status_code=502, detail=f"Could not extract MFA parameters: {e}")

    # Determine MFA type
    mfa_type_raw = payload.get("challenges", [{}])[0].get("type", "").upper()
    mfa_type = "SMS" if "SMS" in mfa_type_raw else ("EMAIL" if "EMAIL" in mfa_type_raw else "APP")

    # Extract contact (partial email/phone shown to user)
    contact = ""
    m2 = re.search(r'data-confirm-contact="([^"]+)"', html)
    if m2:
        contact = m2.group(1)

    # Extract form token for the validation POST
    token_form = _extract_form_token(html) or ""

    # Trigger MFA code delivery via API
    if user_hash and start_path and otp_id:
        try:
            api_url = f"{BOURSO_API}/_user__{user_hash}__/session/challenge/{start_path}/{otp_id}"
            api_resp = await client.post(
                api_url,
                json={"formState": form_state},
                headers={"Accept": "application/json", "Content-Type": "application/json"},
            )
            log.info("MFA start %s → %d", start_path, api_resp.status_code)
        except Exception as e:
            log.warning("MFA trigger failed: %s", e)

    return {
        "client": client,
        "user_hash": user_hash,
        "mfa_required": True,
        "mfa_type": mfa_type,
        "mfa_contact": contact,
        "mfa_state": {
            "otp_id": otp_id,
            "form_state": form_state,
            "check_path": check_path,
            "token_form": token_form,
        },
    }


async def _complete_mfa(state: dict, code: str) -> dict:
    """
    Complete the MFA flow by polling until validation succeeds, then
    submitting the OTP code and confirming the session.
    """
    client     = state["client"]
    user_hash  = state.get("user_hash", "")
    mfa        = state["mfa_state"]
    otp_id     = mfa["otp_id"]
    form_state = mfa["form_state"]
    check_path = mfa["check_path"]
    token_form = mfa["token_form"]

    # Check MFA validation status
    if user_hash and check_path and otp_id:
        check_url = f"{BOURSO_API}/_user__{user_hash}__/session/challenge/{check_path}/{otp_id}"
        for _ in range(10):
            try:
                resp = await client.post(
                    check_url,
                    json={"formState": form_state, "otp": code},
                    headers={"Accept": "application/json", "Content-Type": "application/json"},
                )
                data = resp.json()
                log.info("MFA check → %d %s", resp.status_code, data)
                if data.get("success"):
                    break
            except Exception as e:
                log.warning("MFA check error: %s", e)
            await asyncio.sleep(2)

    # POST /securisation/validation to finalize
    final_resp = await client.post(
        "/securisation/validation",
        data={"form[_token]": token_form, "form[otp]": code},
        headers={"Content-Type": "application/x-www-form-urlencoded"},
    )

    final_html = final_resp.text
    if "se-deconnecter" not in final_html:
        # One more attempt: GET /
        home = await client.get("/")
        final_html = home.text

    if "se-deconnecter" not in final_html:
        await client.aclose()
        raise HTTPException(status_code=401, detail="MFA validation failed — invalid or expired code")

    user_hash = _extract_user_hash(final_html) or user_hash
    return {"client": client, "user_hash": user_hash}


# ─── Account fetching ─────────────────────────────────────────────────────────

async def _fetch_accounts(client: httpx.AsyncClient, user_hash: Optional[str]) -> list[dict]:
    """Fetch all BoursoBank accounts with balances, positions (for trading accounts), and transactions."""
    resp = await client.get(
        "/dashboard/liste-comptes",
        params={"rumroute": "dashboard.new_accounts", "_hinclude": "1"},
        headers={"X-Requested-With": "XMLHttpRequest"},
    )
    resp.raise_for_status()
    accounts_html = resp.text

    accounts = _parse_accounts_html(accounts_html)
    log.info("BoursoBank: found %d accounts", len(accounts))

    # Fetch positions + transactions concurrently per account
    tasks = [_enrich_account(client, acc, user_hash) for acc in accounts]
    enriched = await asyncio.gather(*tasks, return_exceptions=True)

    result = []
    for acc, enriched_acc in zip(accounts, enriched):
        if isinstance(enriched_acc, Exception):
            log.warning("Enrichment failed for account %s: %s", acc["id"], enriched_acc)
            acc["positions"] = []
            acc["transactions"] = []
            result.append(acc)
        else:
            result.append(enriched_acc)

    return result


def _parse_accounts_html(html: str) -> list[dict]:
    """
    Parse the accounts list HTML.
    Each account block contains: data-account-id (or id attr), name, balance, kind.
    """
    accounts = []
    # BoursoBank account rows: look for data-account attributes or known HTML structure
    # Pattern: <li ... data-account-label="..." data-account-id="..." data-account-balance="..." data-account-kind="...">
    for m in re.finditer(
        r'data-account-label="([^"]*)"[^>]*data-account-id="([^"]*)"[^>]*'
        r'data-account-balance="([^"]*)"[^>]*data-account-kind="([^"]*)"',
        html,
    ):
        name, acc_id, balance_str, kind = m.group(1), m.group(2), m.group(3), m.group(4)
        accounts.append(_make_account(acc_id, name, balance_str, kind))

    if not accounts:
        # Fallback: more permissive pattern — look for account data in any order
        for m in re.finditer(
            r'data-account-id="([^"]*)".*?data-account-label="([^"]*)".*?'
            r'data-account-balance="([^"]*)".*?data-account-kind="([^"]*)"',
            html, re.DOTALL,
        ):
            acc_id, name, balance_str, kind = m.group(1), m.group(2), m.group(3), m.group(4)
            accounts.append(_make_account(acc_id, name, balance_str, kind))

    return accounts


def _make_account(acc_id: str, name: str, balance_str: str, kind: str) -> dict:
    balance = _parse_amount(balance_str)
    acc_type = _account_type(name.lower(), kind)
    return {
        "id": f"bourso_{acc_id}",
        "rawId": acc_id,
        "name": name,
        "type": acc_type,
        "balance": balance,
        "positions": [],
        "transactions": [],
    }


def _parse_amount(s: str) -> float:
    """Parse BoursoBank amount string: '1 234,56' or '1234.56' → float."""
    s = s.strip().replace("\xa0", "").replace(" ", "").replace(",", ".")
    s = s.replace("+", "").replace("€", "")
    try:
        return float(s)
    except ValueError:
        return 0.0


async def _enrich_account(client: httpx.AsyncClient, acc: dict, user_hash: Optional[str]) -> dict:
    """Add positions (for trading accounts) and recent transactions to an account."""
    acc_type = acc["type"]
    raw_id   = acc["rawId"]

    # Positions: only for investment accounts
    if acc_type in ("PEA", "COMPTE_TITRES") and user_hash:
        acc["positions"] = await _fetch_positions(client, user_hash, raw_id)

    # Transactions: last 90 days
    acc["transactions"] = await _fetch_transactions(client, raw_id)

    return acc


async def _fetch_positions(client: httpx.AsyncClient, user_hash: str, account_id: str) -> list[dict]:
    """Fetch portfolio positions for a trading account via the trading summary API."""
    url = f"{BOURSO_API}/_user__{user_hash}__/trading/accounts/summary/{account_id}"
    try:
        resp = await client.get(
            url,
            params={"_host": "tradingboard.boursorama.com", "position": "ACCOUNTING", "responseFormat": "true"},
            headers={"Accept": "application/json"},
        )
        if resp.status_code == 404:
            return []
        resp.raise_for_status()
        data = resp.json()
    except Exception as e:
        log.warning("Positions fetch failed for %s: %s", account_id, e)
        return []

    positions = []
    for item in data if isinstance(data, list) else data.get("list", []):
        symbol   = item.get("symbol", "")
        label    = item.get("label", "")
        quantity = _safe_float(item.get("quantity", {}).get("value", 0))
        buy_price = _safe_float(item.get("buyingPrice", item.get("buying_price", {}) if isinstance(item.get("buying_price"), dict) else {"value": 0}).get("value", 0))
        last_price = _safe_float(item.get("last", {}).get("value", 0))

        if not symbol or quantity == 0:
            continue

        # Fetch ISIN via instrument quote endpoint
        isin = await _fetch_isin(client, symbol)

        positions.append({
            "isin": isin,
            "symbol": symbol,
            "label": label,
            "quantity": quantity,
            "buyingPrice": buy_price,
            "currentPrice": last_price,
        })

    return positions


async def _fetch_isin(client: httpx.AsyncClient, symbol: str) -> Optional[str]:
    """Fetch ISIN for a BoursoBank symbol via the public instrument quote API."""
    url = f"{BOURSO_API}/_public_/feed/instrument/quote/{symbol}"
    try:
        resp = await client.get(
            url,
            params={"_host": "tradingboard.boursorama.com"},
            headers={"Accept": "application/json"},
        )
        if resp.status_code != 200:
            return None
        data = resp.json()
        return data.get("isin") or data.get("d", {}).get("isin")
    except Exception as e:
        log.debug("ISIN fetch failed for %s: %s", symbol, e)
        return None


async def _fetch_transactions(client: httpx.AsyncClient, account_id: str) -> list[dict]:
    """Fetch the last 90 days of transactions via BoursoBank CSV export."""
    to_date   = date.today()
    from_date = to_date - timedelta(days=90)
    try:
        resp = await client.get(
            "/budget/exporter-mouvements",
            params={
                "movementSearch[selectedAccount]": account_id,
                "movementSearch[startDate]": from_date.strftime("%d/%m/%Y"),
                "movementSearch[endDate]": to_date.strftime("%d/%m/%Y"),
                "movementSearch[format]": "CSV",
                "movementSearch[fullTime]": "0",
            },
            follow_redirects=True,
        )
        if resp.status_code not in (200, 302):
            return []
        content = resp.content
        # Strip BOM
        if content.startswith(b"\xef\xbb\xbf"):
            content = content[3:]
        text = content.decode("utf-8", errors="replace")
    except Exception as e:
        log.warning("Transactions fetch failed for %s: %s", account_id, e)
        return []

    transactions = []
    reader = csv.DictReader(io.StringIO(text), delimiter=";")
    for row in reader:
        date_str  = row.get("dateOp", row.get("Date opération", "")).strip()
        label     = row.get("label", row.get("Libellé", "")).strip()
        amount_s  = row.get("amount", row.get("Montant", "")).strip()
        category  = row.get("category", row.get("Catégorie", "")).strip()

        try:
            # Parse DD/MM/YYYY
            d = date(*reversed([int(x) for x in date_str.split("/")]))
        except Exception:
            continue

        try:
            amount = float(amount_s.replace(",", ".").replace(" ", "").replace("\xa0", ""))
        except ValueError:
            continue

        transactions.append({
            "date": d.isoformat(),
            "label": label,
            "amount": amount,
            "category": category,
        })

    return transactions


def _safe_float(val) -> float:
    try:
        return float(val)
    except (TypeError, ValueError):
        return 0.0


# ─── Endpoints ────────────────────────────────────────────────────────────────

class InitiateRequest(BaseModel):
    customerId: str
    password: str


class CompleteRequest(BaseModel):
    processId: str
    code: str


class AccountsRequest(BaseModel):
    sessionCookies: str


@app.post("/initiate")
async def initiate(req: InitiateRequest):
    _clean_pending()
    log.info("BoursoBank auth initiate for customer %s***", req.customerId[:3])

    state = await _initiate_auth(req.customerId, req.password)
    process_id = str(uuid.uuid4())
    state["created_at"] = time.time()
    _pending[process_id] = state

    if state["mfa_required"]:
        return {
            "processId": process_id,
            "mfaRequired": True,
            "mfaType": state.get("mfa_type", "UNKNOWN"),
            "contact": state.get("mfa_contact", ""),
        }

    # No MFA — auth complete, return cookies immediately
    cookies_str = _serialize_cookies(state["client"])
    _pending.pop(process_id, None)
    return {
        "processId": process_id,
        "mfaRequired": False,
        "sessionCookies": cookies_str,
    }


@app.post("/complete")
async def complete(req: CompleteRequest):
    state = _pending.get(req.processId)
    if not state:
        raise HTTPException(status_code=404, detail="processId not found or expired — please re-authenticate")

    if not state.get("mfa_required"):
        raise HTTPException(status_code=400, detail="MFA was not required for this session")

    log.info("BoursoBank MFA complete for processId %s", req.processId)
    result = await _complete_mfa(state, req.code)
    state.update(result)

    cookies_str = _serialize_cookies(state["client"])
    _pending.pop(req.processId, None)
    return {"sessionCookies": cookies_str}


@app.post("/accounts")
async def accounts(req: AccountsRequest):
    try:
        cookies_dict = json.loads(req.sessionCookies)
    except json.JSONDecodeError:
        raise HTTPException(status_code=400, detail="Invalid sessionCookies format")

    client = _make_client(cookies_dict)
    try:
        # Verify session is still valid
        home = await client.get("/")
        if "se-deconnecter" not in home.text:
            raise HTTPException(status_code=401, detail="BoursoBank session expired — please re-authenticate")

        user_hash = _extract_user_hash(home.text)
        result = await _fetch_accounts(client, user_hash)
    finally:
        await client.aclose()

    return result


@app.get("/health")
async def health():
    return {"status": "ok"}
