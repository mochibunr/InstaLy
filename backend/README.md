# InstaLy Instagram authentication backend

This service bridges the Android client to Instagram through instagrapi.

Run with Python 3.10+, install requirements, set the session/token secrets, and run uvicorn behind HTTPS.

INSTALY_SESSION_KEY is a Fernet key used to encrypt instagrapi settings at rest. INSTALY_TOKEN_KEY is a long random secret used for bearer tokens. INSTALY_SESSION_DIR is a private session directory.

Generate a Fernet key with: python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"

POST /v1/auth/login accepts username/password. If Instagram requires a verification code, the API returns two_factor_required and the client sends the code to /v1/auth/verify. Successful login returns an opaque InstaLy bearer token. The Android app stores only that token, encrypted with Android Keystore.

Passwords, verification codes, cookies, session IDs, and raw instagrapi settings must never be logged.
