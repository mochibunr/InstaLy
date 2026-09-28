package com.mochibunr.instaly.auth;

import android.content.Context;

/**
 * Client-side auth state.
 *
 * Instagram credentials are never persisted here. The backend exchanges a
 * login for an InstaLy session token; this class stores that token encrypted.
 */
public final class InstagramAuthProviderImpl implements InstagramAuthProvider {
    private final AuthSessionStore sessions;

    public InstagramAuthProviderImpl(Context context) {
        sessions = new AuthSessionStore(context.getApplicationContext());
    }

    @Override
    public boolean isConnected() {
        return sessions.isConnected();
    }

    @Override
    public void beginLogin() {
        // Real login is performed by the configured HTTPS auth backend.
        // Do not put Instagram username/password persistence in this class.
    }

    public void acceptBackendSession(String sessionToken) {
        sessions.saveSessionToken(sessionToken);
    }

    public String getBackendSession() {
        return sessions.readSessionToken();
    }

    @Override
    public void logout() {
        sessions.clear();
    }
}
