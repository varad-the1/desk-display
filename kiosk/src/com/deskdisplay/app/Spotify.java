package com.deskdisplay.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Spotify Web API client using the Authorization Code + PKCE flow (no client secret).
 * Tokens live in private SharedPreferences. The user logs in once; the refresh token keeps it working.
 */
public class Spotify {
    public static final String REDIRECT = "http://127.0.0.1:8080/spotify/callback";
    private static final String SCOPES =
            "user-read-playback-state user-modify-playback-state user-read-recently-played playlist-read-private playlist-read-collaborative";
    private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";

    private final SharedPreferences sp;

    public Spotify(Context c) {
        sp = c.getSharedPreferences("spotify", Context.MODE_PRIVATE);
    }

    /** Spotify is optional: you bring your own (free) developer app. Saved from SYSTEM > Spotify. */
    public String clientId() {
        return sp.getString("client_id", "").trim();
    }

    public void setClientId(String id) {
        String v = id == null ? "" : id.trim();
        if (!v.equals(clientId())) logout();   // a different app means a different login
        sp.edit().putString("client_id", v).apply();
    }

    public boolean connected() {
        return sp.getString("refresh", "").length() > 0;
    }

    public void logout() {
        String id = clientId();   // log out of the account but keep the Client ID the user entered
        sp.edit().clear().putString("client_id", id).apply();
    }

    private static String rand(int n) {
        String a = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";
        SecureRandom r = new SecureRandom();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(a.charAt(r.nextInt(a.length())));
        return b.toString();
    }

    /** URL to open in the browser; the user logs in and is sent back to our local server. */
    public String authUrl() throws Exception {
        if (clientId().length() == 0) throw new Exception("Add your Spotify Client ID first (SYSTEM > Spotify)");
        String verifier = rand(64), state = rand(16);
        byte[] h = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes("US-ASCII"));
        String challenge = Base64.encodeToString(h, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        sp.edit().putString("verifier", verifier).putString("state", state).apply();
        return "https://accounts.spotify.com/authorize?client_id=" + clientId()
                + "&response_type=code&redirect_uri=" + URLEncoder.encode(REDIRECT, "UTF-8")
                + "&code_challenge_method=S256&code_challenge=" + challenge
                + "&state=" + state + "&scope=" + URLEncoder.encode(SCOPES, "UTF-8");
    }

    /** Returns null on success, otherwise a message. */
    public String handleCallback(String code, String state, String error) {
        if (error != null && error.length() > 0) return "Spotify said: " + error;
        if (code == null || state == null || !state.equals(sp.getString("state", "")))
            return "That login did not match this device. Please try again from the tablet.";
        try {
            String body = "grant_type=authorization_code&code=" + URLEncoder.encode(code, "UTF-8")
                    + "&redirect_uri=" + URLEncoder.encode(REDIRECT, "UTF-8")
                    + "&client_id=" + clientId()
                    + "&code_verifier=" + URLEncoder.encode(sp.getString("verifier", ""), "UTF-8");
            String[] r = http("POST", TOKEN_URL, "application/x-www-form-urlencoded", body, null);
            if (!r[0].equals("200")) return "Token error " + r[0] + ": " + r[1];
            save(new JSONObject(r[1]));
            sp.edit().remove("verifier").remove("state").apply();
            return null;
        } catch (Exception e) {
            return "Login failed: " + e.getMessage();
        }
    }

    private void save(JSONObject j) throws Exception {
        SharedPreferences.Editor e = sp.edit();
        e.putString("access", j.getString("access_token"));
        e.putLong("expiry", System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000);
        if (j.has("refresh_token")) e.putString("refresh", j.getString("refresh_token"));
        e.apply();
    }

    private synchronized String token() throws Exception {
        String acc = sp.getString("access", "");
        if (acc.length() > 0 && System.currentTimeMillis() < sp.getLong("expiry", 0) - 60000) return acc;
        String body = "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(sp.getString("refresh", ""), "UTF-8")
                + "&client_id=" + clientId();
        String[] r = http("POST", TOKEN_URL, "application/x-www-form-urlencoded", body, null);
        if (!r[0].equals("200")) {
            if (r[0].equals("400") || r[0].equals("401")) logout(); // refresh token revoked: ask for login again
            throw new IOException("token refresh " + r[0]);
        }
        save(new JSONObject(r[1]));
        return sp.getString("access", "");
    }

    /** Returns {statusCode, bodyText}. statusCode "0" means a local/network failure. */
    public String[] call(String method, String path, String body) {
        try {
            String[] r = http(method, "https://api.spotify.com" + path, "application/json", body, token());
            if (r[0].equals("401")) {
                sp.edit().putLong("expiry", 0).apply();
                r = http(method, "https://api.spotify.com" + path, "application/json", body, token());
            }
            return r;
        } catch (Exception e) {
            return new String[]{"0", e.getMessage() == null ? "error" : e.getMessage()};
        }
    }

    static String[] http(String method, String url, String ctype, String body, String bearer) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
        if (body != null && body.length() > 0) {
            byte[] b = body.getBytes("UTF-8");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", ctype);
            c.setFixedLengthStreamingMode(b.length);
            OutputStream o = c.getOutputStream();
            o.write(b);
            o.close();
        } else if (method.equals("PUT") || method.equals("POST")) {
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(0);
            c.getOutputStream().close();
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String txt = "";
        if (in != null) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            txt = bo.toString("UTF-8");
        }
        return new String[]{String.valueOf(code), txt};
    }
}
