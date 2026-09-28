import base64
import hashlib
import hmac
import json
import os
import secrets
import time
from pathlib import Path
from typing import Optional

from cryptography.fernet import Fernet
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field
from instagrapi import Client
from instagrapi.exceptions import (
    BadPassword,
    TwoFactorRequired,
    ChallengeRequired,
    LoginRequired,
    ClientThrottledError,
)

SESSION_DIR = Path(os.getenv("INSTALY_SESSION_DIR", "./sessions"))
SESSION_DIR.mkdir(parents=True, exist_ok=True)
FERNET = Fernet(os.environ["INSTALY_SESSION_KEY"].encode())
TOKEN_KEY = os.environ["INSTALY_TOKEN_KEY"].encode()

app = FastAPI(title="InstaLy Auth", version="1.0.0")
origins = [x.strip() for x in os.getenv("INSTALY_ALLOWED_ORIGINS", "").split(",") if x.strip()]
app.add_middleware(
    CORSMiddleware,
    allow_origins=origins or ["*"],
    allow_methods=["POST", "GET"],
    allow_headers=["Authorization", "Content-Type"],
)

class LoginRequest(BaseModel):
    username: str = Field(min_length=1, max_length=100)
    password: str = Field(min_length=1, max_length=256)

class VerifyRequest(BaseModel):
    login_id: str = Field(min_length=20, max_length=200)
    code: str = Field(min_length=4, max_length=12)

class SessionResponse(BaseModel):
    status: str
    token: Optional[str] = None
    login_id: Optional[str] = None
    message: Optional[str] = None

def token_for(session_id: str) -> str:
    body = base64.urlsafe_b64encode(session_id.encode()).decode().rstrip("=")
    sig = hmac.new(TOKEN_KEY, body.encode(), hashlib.sha256).hexdigest()
    return body + "." + sig

def session_id_from_token(token: str) -> str:
    try:
        body, sig = token.split(".", 1)
        expected = hmac.new(TOKEN_KEY, body.encode(), hashlib.sha256).hexdigest()
        if not hmac.compare_digest(sig, expected):
            raise ValueError()
        return base64.urlsafe_b64decode(body + "=" * (-len(body) % 4)).decode()
    except Exception:
        raise HTTPException(status_code=401, detail="invalid_session")

def encrypted_path(session_id: str) -> Path:
    safe = hashlib.sha256(session_id.encode()).hexdigest()
    return SESSION_DIR / (safe + ".bin")

def save_settings(session_id: str, settings: dict) -> None:
    raw = json.dumps(settings, separators=(",", ":")).encode()
    encrypted_path(session_id).write_bytes(FERNET.encrypt(raw))

def load_settings(session_id: str) -> dict:
    path = encrypted_path(session_id)
    if not path.exists():
        raise HTTPException(status_code=401, detail="session_expired")
    try:
        return json.loads(FERNET.decrypt(path.read_bytes()))
    except Exception:
        raise HTTPException(status_code=401, detail="session_corrupt")

pending: dict[str, dict] = {}

def new_client() -> Client:
    return Client()

@app.get("/health")
def health():
    return {"ok": True}

@app.post("/v1/auth/login", response_model=SessionResponse)
def login(req: LoginRequest):
    login_id = secrets.token_urlsafe(24)
    client = new_client()
    try:
        client.login(req.username, req.password)
        save_settings(login_id, client.get_settings())
        return SessionResponse(status="authenticated", token=token_for(login_id))
    except TwoFactorRequired:
        pending[login_id] = {
            "username": req.username,
            "password": req.password,
            "client": client,
            "created": time.time(),
        }
        return SessionResponse(
            status="two_factor_required",
            login_id=login_id,
            message="Instagram requires a verification code.",
        )
    except ChallengeRequired:
        pending[login_id] = {
            "username": req.username,
            "password": req.password,
            "client": client,
            "created": time.time(),
        }
        return SessionResponse(
            status="challenge_required",
            login_id=login_id,
            message="Instagram requires additional verification in its official flow.",
        )
    except BadPassword:
        raise HTTPException(status_code=401, detail="bad_credentials")
    except ClientThrottledError:
        raise HTTPException(status_code=429, detail="instagram_rate_limited")
    finally:
        # Do not retain request credentials after a normal successful login.
        # Pending verification keeps them only until verification completes.
        if login_id not in pending:
            req.password = "[discarded]"

@app.post("/v1/auth/verify", response_model=SessionResponse)
def verify(req: VerifyRequest):
    item = pending.get(req.login_id)
    if not item:
        raise HTTPException(status_code=404, detail="login_expired")
    if time.time() - item["created"] > 600:
        pending.pop(req.login_id, None)
        raise HTTPException(status_code=410, detail="login_expired")

    client: Client = item["client"]
    try:
        client.login(item["username"], item["password"], verification_code=req.code)
        save_settings(req.login_id, client.get_settings())
        pending.pop(req.login_id, None)
        return SessionResponse(status="authenticated", token=token_for(req.login_id))
    except TwoFactorRequired:
        raise HTTPException(status_code=401, detail="verification_code_rejected")
    except ChallengeRequired:
        raise HTTPException(status_code=409, detail="manual_instagram_challenge_required")
    except BadPassword:
        pending.pop(req.login_id, None)
        raise HTTPException(status_code=401, detail="bad_credentials")

@app.get("/v1/auth/me")
def me(authorization: str = ""):
    if not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="missing_session")
    sid = session_id_from_token(authorization[7:])
    settings = load_settings(sid)
    return {"authenticated": True, "user_id": settings.get("authorization_data", {}).get("ds_user_id")}

