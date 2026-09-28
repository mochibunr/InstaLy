package com.mochibunr.instaly.auth;

import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class InstagramAuthProviderImpl implements InstagramAuthProvider {
    private static final String API_BASE = BuildConfig.INSTALY_API_BASE_URL;
    private final AuthSessionStore sessions;

    public InstagramAuthProviderImpl(Context context) {
        sessions = new AuthSessionStore(context.getApplicationContext());
    }

    @Override public boolean isConnected() { return sessions.isConnected(); }
    @Override public void beginLogin() {}
    @Override public void logout() { sessions.clear(); }

    public AuthResult login(String username, String password) throws Exception {
        JSONObject body = new JSONObject().put("username", username).put("password", password);
        JSONObject response = post(API_BASE + "/auth/login", body);
        return parse(response);
    }

    public AuthResult verify(String loginId, String code) throws Exception {
        JSONObject body = new JSONObject().put("login_id", loginId).put("code", code);
        JSONObject response = post(API_BASE + "/auth/verify", body);
        return parse(response);
    }

    private AuthResult parse(JSONObject response) {
        String status = response.optString("status", "error");
        String token = response.optString("token", "");
        if ("authenticated".equals(status) && !token.isEmpty()) {
            sessions.saveSessionToken(token);
        }
        return new AuthResult(status, response.optString("login_id", ""), response.optString("message", ""));
    }

    private JSONObject post(String endpoint, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(endpoint).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json");
        try (OutputStream out = c.getOutputStream()) {
            out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        InputStream stream = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = read(stream);
        if (code >= 400) {
            try {
                throw new IOException(new JSONObject(text).optString("detail", "Authentication failed"));
            } catch (org.json.JSONException ignored) {
                throw new IOException("Authentication failed (" + code + ")");
            }
        }
        return new JSONObject(text);
    }

    private String read(InputStream stream) throws IOException {
        if (stream == null) return "";
        try (BufferedReader r = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder b = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) b.append(line);
            return b.toString();
        }
    }

    public String getBackendSession() { return sessions.readSessionToken(); }

    public static final class AuthResult {
        public final String status;
        public final String loginId;
        public final String message;
        AuthResult(String status, String loginId, String message) {
            this.status = status; this.loginId = loginId; this.message = message;
        }
    }
}
