package com.deskdisplay.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;

/**
 * Small HTTP server on port 8080.
 *  - /spotify/callback      Spotify login redirect
 *  - /ping                  lets the phone app find the tablet (no auth)
 *  - /remote                the phone control page (no auth; the page itself sends the PIN)
 *  - /api/hello|state|cmd   JSON API, every call needs the 6-digit PIN (X-Pin header or ?pin=)
 */
public class LocalServer extends Thread {
    public interface Host {
        void spotifyDone(String error);
        String evalJs(String js);        // runs JS in the page, returns the decoded string result (or null)
        String pin();
        byte[] asset(String name);       // file from the app's assets, or null
        String deviceName();
        java.io.File wwwDir();           // where pushed page files are kept
        String otaStatus();              // JSON list of pushed files
        void otaApplied();               // reload the page so pushed files show up
        void otaReset();                 // drop pushed files, back to the built-in page
    }

    private final Spotify spotify;
    private final Host host;
    private ServerSocket ss;
    private int fails = 0;
    private long failWindow = 0;

    public LocalServer(Spotify spotify, Host host) {
        super("local-server");
        this.spotify = spotify;
        this.host = host;
    }

    @Override
    public void run() {
        try {
            ss = new ServerSocket(8080);
            while (!isInterrupted()) {
                final Socket s = ss.accept();
                new Thread(new Runnable() {
                    public void run() { handle(s); }
                }).start();
            }
        } catch (IOException e) {
            // port busy or closed: nothing else depends on this
        }
    }

    public void shutdown() {
        interrupt();
        try { if (ss != null) ss.close(); } catch (IOException e) { }
    }

    private static Map<String, String> parse(String q) {
        Map<String, String> m = new HashMap<String, String>();
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            if (i < 0) continue;
            try { m.put(URLDecoder.decode(kv.substring(0, i), "UTF-8"), URLDecoder.decode(kv.substring(i + 1), "UTF-8")); }
            catch (Exception e) { }
        }
        return m;
    }

    private static String html(String title, String text) {
        return "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<body style='background:#000;color:#fff;font-family:monospace;padding:12vw 8vw;text-align:center'>"
                + "<h1>" + title + "</h1><p style='color:#aaa'>" + text + "</p></body>";
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 16384) throw new IOException("line too long");
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    private synchronized boolean pinOk(String given) {
        long now = System.currentTimeMillis();
        if (now - failWindow > 60000) { failWindow = now; fails = 0; }
        if (fails >= 10) return false; // locked out for the rest of the minute
        boolean ok = given != null && given.length() > 0 && given.equals(host.pin());
        if (!ok) fails++;
        return ok;
    }

    private static void send(Socket s, int status, String type, byte[] body) throws IOException {
        String st = status == 200 ? "OK" : status == 401 ? "Unauthorized" : status == 404 ? "Not Found"
                : status == 429 ? "Too Many Requests" : status == 400 ? "Bad Request" : "Error";
        OutputStream o = s.getOutputStream();
        o.write(("HTTP/1.1 " + status + " " + st + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));
        o.write(body);
        o.flush();
    }

    private static void json(Socket s, int status, String text) throws IOException {
        send(s, status, "application/json; charset=utf-8", text.getBytes("UTF-8"));
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(6000);
            InputStream in = s.getInputStream();
            String line = readLine(in);
            if (line == null) return;
            String[] p = line.split(" ");
            String method = p[0];
            String target = p.length > 1 ? p[1] : "/";
            int contentLength = 0;
            String headerPin = null, h;
            while ((h = readLine(in)) != null && h.length() > 0) {
                String low = h.toLowerCase();
                if (low.startsWith("content-length:")) contentLength = Integer.parseInt(h.substring(15).trim());
                else if (low.startsWith("x-pin:")) headerPin = h.substring(6).trim();
            }
            String path = target, query = "";
            int q = target.indexOf('?');
            if (q >= 0) { path = target.substring(0, q); query = target.substring(q + 1); }
            Map<String, String> params = parse(query);

            // the PIN is checked before any body is read, so only a paired device can make the tablet buffer a big upload
            if (path.startsWith("/api/")) {
                String pin0 = headerPin != null ? headerPin : params.get("pin");
                if (!pinOk(pin0)) {
                    json(s, fails >= 10 ? 429 : 401, "{\"error\":\"" + (fails >= 10 ? "locked, wait a minute" : "wrong pin") + "\"}");
                    return;
                }
            }
            byte[] body = new byte[0];
            boolean bigOk = path.startsWith("/api/ota/");
            if (contentLength > 0) {
                if (contentLength > (bigOk ? 12000000 : 1000000)) { json(s, 400, "{\"error\":\"too large\"}"); return; }
                body = new byte[contentLength];
                int off = 0;
                while (off < contentLength) {
                    int n = in.read(body, off, contentLength - off);
                    if (n < 0) break;
                    off += n;
                }
                if (off != contentLength) { json(s, 400, "{\"error\":\"incomplete upload\"}"); return; }
            }

            if (path.equals("/spotify/callback")) {
                String err = spotify.handleCallback(params.get("code"), params.get("state"), params.get("error"));
                send(s, 200, "text/html; charset=utf-8", (err == null ? html("Spotify connected", "Going back to Desk Display...")
                        : html("Spotify login failed", err)).getBytes("UTF-8"));
                host.spotifyDone(err);
                return;
            }
            if (path.equals("/ping")) {
                json(s, 200, "{\"app\":\"deskdisplay\",\"name\":\"" + host.deviceName().replace("\"", "") + "\"}");
                return;
            }
            if (path.equals("/remote")) {
                byte[] page = host.asset("remote.html");
                if (page == null) send(s, 404, "text/plain", "missing".getBytes("UTF-8"));
                else send(s, 200, "text/html; charset=utf-8", page);
                return;
            }
            if (path.startsWith("/api/")) {
                if (path.equals("/api/hello")) {
                    json(s, 200, "{\"ok\":true,\"name\":\"" + host.deviceName().replace("\"", "") + "\"}");
                } else if (path.equals("/api/state") && method.equals("GET")) {
                    String r = host.evalJs("window.__dumpState&&window.__dumpState()");
                    json(s, r == null ? 500 : 200, r == null ? "{\"error\":\"page not ready\"}" : r);
                } else if (path.equals("/api/state") && method.equals("POST")) {
                    String r = host.evalJs("window.__loadState&&window.__loadState(" + org.json.JSONObject.quote(new String(body, "UTF-8")) + ")");
                    json(s, r == null ? 500 : 200, "{\"result\":" + org.json.JSONObject.quote(r == null ? "failed" : r) + "}");
                } else if (path.equals("/api/spotify") && method.equals("GET")) {
                    // search / playlists for the phone's song picker (read-only calls only)
                    String sp = params.get("p");
                    if (sp == null || !(sp.startsWith("/v1/search?") || sp.startsWith("/v1/me/playlists"))) {
                        json(s, 400, "{\"error\":\"not allowed\"}");
                    } else if (!spotify.connected()) {
                        json(s, 200, "{\"status\":401,\"body\":null}");
                    } else {
                        String[] r = spotify.call("GET", sp, null);
                        boolean ok = r[0].equals("200") && r[1].trim().startsWith("{");
                        json(s, 200, "{\"status\":" + r[0] + ",\"body\":" + (ok ? r[1] : "null") + "}");
                    }
                } else if (path.equals("/api/ota") && method.equals("GET")) {
                    json(s, 200, host.otaStatus());
                } else if (path.equals("/api/ota/file") && (method.equals("PUT") || method.equals("POST"))) {
                    String name = params.get("name");
                    if (name == null || !name.matches("index\\.html|remote\\.html|art_[a-z0-9]+\\.jpg")) {
                        json(s, 400, "{\"error\":\"file name not allowed\"}");
                    } else if (body.length == 0) {
                        json(s, 400, "{\"error\":\"empty\"}");
                    } else if (name.endsWith(".html") && (body.length < 5000 || !new String(body, "UTF-8").contains("</html>"))) {
                        json(s, 400, "{\"error\":\"that does not look like a complete page\"}");
                    } else {
                        java.io.File d = host.wwwDir();
                        java.io.File tmp = new java.io.File(d, name + ".tmp"), dst = new java.io.File(d, name);
                        java.io.FileOutputStream fo = new java.io.FileOutputStream(tmp);
                        fo.write(body);
                        fo.getFD().sync();
                        fo.close();
                        if (dst.exists()) dst.delete();
                        if (!tmp.renameTo(dst)) { json(s, 500, "{\"error\":\"could not save\"}"); return; }
                        json(s, 200, "{\"ok\":true,\"name\":" + org.json.JSONObject.quote(name) + ",\"size\":" + body.length + "}");
                    }
                } else if (path.equals("/api/ota/reload") && method.equals("POST")) {
                    host.otaApplied();
                    json(s, 200, "{\"ok\":true}");
                } else if (path.equals("/api/ota/reset") && method.equals("POST")) {
                    host.otaReset();
                    json(s, 200, "{\"ok\":true}");
                } else if (path.equals("/api/backup") && method.equals("GET")) {
                    String r = host.evalJs("window.__backup&&window.__backup()");
                    json(s, r == null ? 500 : 200, r == null ? "{\"error\":\"page not ready\"}" : r);
                } else if (path.equals("/api/restore") && method.equals("POST")) {
                    String r = host.evalJs("window.__restore&&window.__restore(" + org.json.JSONObject.quote(new String(body, "UTF-8")) + ")");
                    json(s, r == null ? 500 : 200, "{\"result\":" + org.json.JSONObject.quote(r == null ? "failed" : r) + "}");
                } else if (path.equals("/api/cmd") && method.equals("POST")) {
                    String r = host.evalJs("window.__cmd&&window.__cmd(" + org.json.JSONObject.quote(new String(body, "UTF-8")) + ")");
                    json(s, r == null ? 500 : 200, "{\"result\":" + org.json.JSONObject.quote(r == null ? "failed" : r) + "}");
                } else {
                    json(s, 404, "{\"error\":\"unknown call\"}");
                }
                return;
            }
            send(s, 200, "text/html; charset=utf-8", html("Desk Display", "running").getBytes("UTF-8"));
        } catch (Exception e) {
            // ignore broken connections
        } finally {
            try { s.close(); } catch (IOException e) { }
        }
    }
}
