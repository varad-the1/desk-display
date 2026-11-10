package com.deskdisplay.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import android.app.SearchManager;
import android.content.ComponentName;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.List;

public class MainActivity extends Activity {
    private WebView web;
    private PowerManager.WakeLock wl;
    private ValueCallback<Uri[]> chooser;
    private static final int PICK = 77;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "deskdisplay:run");
        wl.acquire();
        WebView.setWebContentsDebuggingEnabled(true); // lets adb/Chrome devtools inspect the page
        installCrashRestart();
        otaValidate();
        spotify = new Spotify(this);
        server = new LocalServer(spotify, new LocalServer.Host() {
            public void spotifyDone(final String error) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        Intent i = new Intent(MainActivity.this, MainActivity.class);
                        i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                        js("window.onSpotifyAuth&&window.onSpotifyAuth(" + JSONObject.quote(error == null ? "connected" : error) + ")");
                    }
                });
            }
            public String evalJs(String code) { return evalBlocking(code); }
            public String pin() { return remotePin(); }
            public String deviceName() { return android.os.Build.MODEL; }
            public File wwwDir() { return otaDirForWrite(); }
            public String otaStatus() { return otaStatusJson(); }
            public void otaApplied() { pageReload(true); }
            public void otaReset() { otaClear(); pageReload(false); }
            public byte[] asset(String name) {
                try {
                    File o = new File(otaDir(), name);   // an update pushed over Wi-Fi wins over the built-in copy
                    java.io.InputStream in = o.isFile() ? new java.io.FileInputStream(o) : getAssets().open(name);
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                    in.close();
                    return bo.toByteArray();
                } catch (Exception e) { return null; }
            }
        });
        server.start();
        buildWeb();
        immersive();
        /* RECORD_AUDIO is only needed by android.media.audiofx.Visualizer (the live spectrum). Nothing is recorded. */
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 88);
        startMedia();
        startWatchdog();
    }

    /* ---------- reliability: crash restart + frozen-page watchdog ---------- */
    /** simple box blur on a small bitmap (separable, edge-clamped) */
    private static void boxBlur(android.graphics.Bitmap b, int r) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h], tmp = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 2; pass++) {
            int[] src = pass == 0 ? px : tmp, dst = pass == 0 ? tmp : px;
            int lenA = pass == 0 ? h : w, lenB = pass == 0 ? w : h;
            for (int a = 0; a < lenA; a++) {
                for (int i = 0; i < lenB; i++) {
                    int sr = 0, sg = 0, sb = 0, n = 0;
                    for (int k = -r; k <= r; k++) {
                        int j = Math.max(0, Math.min(lenB - 1, i + k));
                        int p = pass == 0 ? src[a * w + j] : src[j * w + a];
                        sr += (p >> 16) & 255; sg += (p >> 8) & 255; sb += p & 255; n++;
                    }
                    int v = 0xFF000000 | ((sr / n) << 16) | ((sg / n) << 8) | (sb / n);
                    if (pass == 0) dst[a * w + i] = v; else dst[i * w + a] = v;
                }
            }
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
    }

    private Spotify spotify;
    private LocalServer server;
    private android.media.audiofx.Visualizer viz;
    private byte[] fftBuf;

    /* ---------- phone remote: run JS in the page for the HTTP server, PIN, address ---------- */
    private String evalBlocking(final String code) {
        final String[] out = new String[1];
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        runOnUiThread(new Runnable() {
            public void run() {
                if (web == null) { latch.countDown(); return; }
                web.evaluateJavascript(code, new ValueCallback<String>() {
                    public void onReceiveValue(String v) { out[0] = v; latch.countDown(); }
                });
            }
        });
        try { latch.await(5, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { }
        String v = out[0];
        if (v == null || v.equals("null")) return null;
        try { // evaluateJavascript gives a JSON value; our functions return strings
            Object o = new org.json.JSONTokener(v).nextValue();
            return o instanceof String ? (String) o : o.toString();
        } catch (Exception e) { return v; }
    }

    private String remotePin() {
        android.content.SharedPreferences p = getSharedPreferences("remote", Context.MODE_PRIVATE);
        String pin = p.getString("pin", "");
        if (pin.length() != 6) {
            pin = newPin();
        }
        return pin;
    }

    private String newPin() {
        java.security.SecureRandom r = new java.security.SecureRandom();
        String pin = String.format("%06d", r.nextInt(1000000));
        getSharedPreferences("remote", Context.MODE_PRIVATE).edit().putString("pin", pin).apply();
        return pin;
    }

    private String lanAddress() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> nis = java.net.NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                java.net.NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                java.util.Enumeration<java.net.InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    java.net.InetAddress a = as.nextElement();
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception e) { }
        return "";
    }
    private volatile long lastBeat = 0;
    private final android.os.Handler wd = new android.os.Handler();

    private void scheduleRestart(long delayMs) {
        try {
            android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE);
            Intent i = new Intent(getApplicationContext(), MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(getApplicationContext(), 7, i,
                    android.app.PendingIntent.FLAG_CANCEL_CURRENT);
            am.set(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMs, pi);
        } catch (Throwable t) { }
    }

    private void installCrashRestart() {
        final Thread.UncaughtExceptionHandler old = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            public void uncaughtException(Thread t, Throwable e) {
                scheduleRestart(2000);
                if (old != null) old.uncaughtException(t, e);
                else android.os.Process.killProcess(android.os.Process.myPid());
            }
        });
    }

    private void startWatchdog() {
        wd.postDelayed(new Runnable() {
            public void run() {
                long now = SystemClock.elapsedRealtime();
                if (lastBeat == 0 && loadedAt != 0 && now - loadedAt > 45000 && otaActive() && web != null) {
                    // a page pushed over Wi-Fi never started: fall back to the built-in page
                    otaClear();
                    loadedAt = now;
                    try { loadPage(); } catch (Throwable t) { }
                }
                // lastBeat == 0 means the page has not started yet; otherwise 90 s of silence = frozen page
                else if (lastBeat != 0 && now - lastBeat > 90000 && web != null) {
                    lastBeat = now;
                    if (otaActive() && ++otaStrikes >= 2) otaClear();   // an update that keeps freezing is rolled back
                    try { loadPage(); } catch (Throwable t) { }
                }
                wd.postDelayed(this, 15000);
            }
        }, 30000);
    }

    /* ---------- updates over Wi-Fi: files pushed to the local server override the built-in pages ---------- */
    private volatile long loadedAt = 0;
    private int otaStrikes = 0;
    private static final String OTA_NAMES = "index\\.html|remote\\.html|art_[a-z0-9]+\\.jpg";

    private File otaDir() { return new File(getFilesDir(), "www"); }

    private long apkTime() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime; } catch (Exception e) { return 0; }
    }

    /** the directory for writing: created on demand and stamped with the app build it was made for */
    private File otaDirForWrite() {
        File d = otaDir();
        d.mkdirs();
        File m = new File(d, ".base");
        if (!m.exists()) {
            try {
                java.io.FileOutputStream o = new java.io.FileOutputStream(m);
                o.write(String.valueOf(apkTime()).getBytes("UTF-8"));
                o.close();
            } catch (Exception e) { }
        }
        return d;
    }

    /** a newly installed app build always wins over files pushed for an older build */
    private void otaValidate() {
        try {
            File m = new File(otaDir(), ".base");
            if (!m.exists()) return;
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(m));
            String t = r.readLine();
            r.close();
            if (t == null || !t.trim().equals(String.valueOf(apkTime()))) otaClear();
        } catch (Exception e) { otaClear(); }
    }

    /** an update is active once a page has been pushed (the other files are copied beside it) */
    private boolean otaActive() { return new File(otaDir(), "index.html").isFile(); }

    /** the page is loaded from the app's own storage when an update is active, otherwise from the built-in assets */
    private void loadPage() {
        if (web == null) return;
        web.loadUrl(otaActive() ? Uri.fromFile(new File(otaDir(), "index.html")).toString() : "file:///android_asset/index.html");
    }

    /** put a copy of every built-in page and painting that has not been pushed next to the pushed ones, so relative links keep working */
    private void otaMaterialize() {
        try {
            File d = otaDirForWrite();
            String[] names = getAssets().list("");
            if (names == null) return;
            for (String n : names) {
                if (!n.matches(OTA_NAMES)) continue;
                File f = new File(d, n);
                if (f.isFile()) continue;
                File tmp = new File(d, n + ".tmp");
                java.io.InputStream in = getAssets().open(n);
                java.io.FileOutputStream o = new java.io.FileOutputStream(tmp);
                byte[] buf = new byte[16384];
                int k;
                while ((k = in.read(buf)) > 0) o.write(buf, 0, k);
                o.close();
                in.close();
                tmp.renameTo(f);
            }
        } catch (Exception e) { }
    }

    private void otaClear() {
        File[] f = otaDir().listFiles();
        if (f != null) for (File x : f) x.delete();
        otaStrikes = 0;
    }

    /** reload the page; with ota=true the pushed files are completed first, with false the built-in page is shown */
    private void pageReload(boolean ota) {
        if (ota) otaMaterialize();
        runOnUiThread(new Runnable() {
            public void run() {
                lastBeat = 0;
                loadedAt = SystemClock.elapsedRealtime();
                otaStrikes = 0;
                try { web.clearCache(true); loadPage(); } catch (Throwable t) { }
            }
        });
    }

    private String otaStatusJson() {
        StringBuilder sb = new StringBuilder("{\"active\":" + otaActive() + ",\"files\":[");
        File[] f = otaDir().listFiles();
        boolean first = true;
        if (f != null) for (File x : f) {
            if (x.getName().startsWith(".") || x.getName().endsWith(".tmp")) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"name\":").append(JSONObject.quote(x.getName())).append(",\"size\":").append(x.length())
              .append(",\"sha1\":").append(JSONObject.quote(sha1(x))).append('}');
        }
        return sb.append("]}").toString();
    }

    private static String sha1(File f) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            java.io.InputStream in = new java.io.FileInputStream(f);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            in.close();
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    /* ---------- media session bridge (Spotify etc.) ---------- */
    private MediaSessionManager msm;
    private MediaController mc;
    private String mediaAccess = "unknown";

    private final MediaController.Callback mcb = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata m) { pushMedia(true); pushQueue(); }
        @Override public void onPlaybackStateChanged(PlaybackState s) { pushMedia(false); }
        @Override public void onQueueChanged(List<MediaSession.QueueItem> q) { pushQueue(); }
        @Override public void onSessionDestroyed() { choose(sessions()); }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sl =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                @Override public void onActiveSessionsChanged(List<MediaController> l) { choose(l); }
            };

    private List<MediaController> sessions() {
        try {
            return msm.getActiveSessions(new ComponentName(this, MediaListener.class));
        } catch (SecurityException e) {
            return null;
        }
    }

    private void startMedia() {
        try {
            msm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            ComponentName cn = new ComponentName(this, MediaListener.class);
            msm.addOnActiveSessionsChangedListener(sl, cn);
            choose(msm.getActiveSessions(cn));
            mediaAccess = "ok";
        } catch (SecurityException e) {
            mediaAccess = "no-access";
        } catch (Throwable e) {
            mediaAccess = "error";
        }
        js("window.onMediaAccess&&window.onMediaAccess(" + JSONObject.quote(mediaAccess) + ")");
    }

    private void choose(List<MediaController> l) {
        MediaController best = null;
        if (l != null) {
            for (MediaController c : l) {
                PlaybackState st = c.getPlaybackState();
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) { best = c; break; }
                if (best == null) best = c;
            }
        }
        if (mc != null) { try { mc.unregisterCallback(mcb); } catch (Exception e) { } }
        mc = best;
        if (mc != null) mc.registerCallback(mcb);
        pushMedia(true);
        pushQueue();
    }

    private String queueJson() {
        org.json.JSONArray arr = new org.json.JSONArray();
        try {
            List<MediaSession.QueueItem> q = mc == null ? null : mc.getQueue();
            if (q != null) {
                for (int i = 0; i < q.size() && i < 100; i++) {
                    MediaSession.QueueItem it = q.get(i);
                    MediaDescription d = it.getDescription();
                    JSONObject o = new JSONObject();
                    o.put("id", it.getQueueId());
                    o.put("mid", d.getMediaId() == null ? "" : d.getMediaId());
                    o.put("title", d.getTitle() == null ? "" : d.getTitle().toString());
                    o.put("sub", d.getSubtitle() == null ? "" : d.getSubtitle().toString());
                    arr.put(o);
                }
            }
        } catch (Exception e) { }
        return arr.toString();
    }

    private void pushQueue() {
        js("window.onQueue&&window.onQueue(" + queueJson() + ")");
    }

    private String mediaJson(boolean withArt) {
        JSONObject o = new JSONObject();
        try {
            if (mc == null) { o.put("active", false); return o.toString(); }
            o.put("active", true);
            o.put("pkg", mc.getPackageName());
            MediaMetadata m = mc.getMetadata();
            PlaybackState s = mc.getPlaybackState();
            String title = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_TITLE);
            String artist = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (artist == null && m != null) artist = m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            String album = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_ALBUM);
            o.put("title", title == null ? "" : title);
            o.put("artist", artist == null ? "" : artist);
            o.put("album", album == null ? "" : album);
            String mid = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
            o.put("mid", mid == null ? "" : mid);
            o.put("dur", m == null ? 0 : m.getLong(MediaMetadata.METADATA_KEY_DURATION));
            boolean playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
            o.put("playing", playing);
            long pos = s == null ? 0 : s.getPosition();
            if (playing) pos += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
            o.put("pos", pos);
            o.put("speed", playing ? s.getPlaybackSpeed() : 0);
            o.put("qactive", s == null ? -1 : s.getActiveQueueItemId());
            if (withArt) {
                String art = "";
                Bitmap b = null;
                if (m != null) {
                    b = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
                    if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                    if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
                }
                if (b != null) {
                    int w = b.getWidth(), h = b.getHeight(), max = 320;
                    if (Math.max(w, h) > max) {
                        float k = (float) max / Math.max(w, h);
                        b = Bitmap.createScaledBitmap(b, Math.max(1, (int) (w * k)), Math.max(1, (int) (h * k)), true);
                    }
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    b.compress(Bitmap.CompressFormat.JPEG, 75, bo);
                    art = "data:image/jpeg;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
                }
                o.put("art", art);
            }
        } catch (Exception e) { }
        return o.toString();
    }

    private void pushMedia(boolean withArt) {
        js("window.onMedia&&window.onMedia(" + mediaJson(withArt) + ")");
    }

    private void bringBack(long delayMs) {
        web.postDelayed(new Runnable() {
            public void run() {
                Intent i = new Intent(MainActivity.this, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            }
        }, delayMs);
    }

    private void mediaCommand(String cmd, String arg) {
        try {
            if (cmd.equals("search")) {
                if (mc != null) {
                    mc.getTransportControls().playFromSearch(arg, new android.os.Bundle());
                } else {
                    Intent i = new Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH);
                    i.setPackage("com.spotify.music");
                    i.putExtra(SearchManager.QUERY, arg);
                    i.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*");
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                    bringBack(6000);
                }
                return;
            }
            if (cmd.equals("back")) { bringBack(0); return; }
            if (cmd.equals("open")) {
                Intent i = getPackageManager().getLaunchIntentForPackage("com.spotify.music");
                if (i != null) { startActivity(i); }
                return;
            }
            if (mc == null) return;
            MediaController.TransportControls t = mc.getTransportControls();
            if (cmd.equals("play")) t.play();
            else if (cmd.equals("pause")) t.pause();
            else if (cmd.equals("next")) t.skipToNext();
            else if (cmd.equals("prev")) t.skipToPrevious();
            else if (cmd.equals("seek")) t.seekTo(Long.parseLong(arg));
            else if (cmd.equals("queue-item")) t.skipToQueueItem(Long.parseLong(arg));
            else if (cmd.equals("uri")) t.playFromUri(Uri.parse(arg), new android.os.Bundle());
            else if (cmd.equals("mid")) t.playFromMediaId(arg, new android.os.Bundle());
        } catch (Exception e) { }
    }

    private void js(final String code) {
        runOnUiThread(new Runnable() {
            public void run() {
                if (web != null) web.evaluateJavascript(code, null);
            }
        });
    }

    private class Bridge {
        @JavascriptInterface
        public String mediaState() { return mediaJson(true); }

        @JavascriptInterface
        public String queueState() { return queueJson(); }

        @JavascriptInterface
        public String deviceName() { return android.os.Build.MODEL; }

        /** Opens another installed app. Returns "ok", or "missing" if it is not installed. */
        @JavascriptInterface
        public String launchApp(final String pkg) {
            final Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) return "missing";
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            runOnUiThread(new Runnable() {
                public void run() { try { startActivity(i); } catch (Exception e) { } }
            });
            return "ok";
        }

        /** Opens the app's page in the Play Store so it can be installed. */
        @JavascriptInterface
        public void openStore(final String pkg) {
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Exception e) { }
                }
            });
        }

        /**
         * A tiny, softened picture of the page as it looks right now, as a data URL. The page stretches it over
         * the screen as a ready-made blur (one-off cost, nothing per frame). "" if it could not be taken.
         */
        @JavascriptInterface
        public String blurSnapshot() {
            final String[] out = new String[]{""};
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        int w = web.getWidth(), h = web.getHeight();
                        if (w > 0 && h > 0) {
                            int sw = Math.max(32, w / 10), sh = Math.max(20, h / 10);
                            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(sw, sh, android.graphics.Bitmap.Config.ARGB_8888);
                            android.graphics.Canvas c = new android.graphics.Canvas(bmp);
                            c.drawColor(android.graphics.Color.BLACK);
                            c.scale(sw / (float) w, sh / (float) h);
                            web.draw(c);
                            boxBlur(bmp, 2);
                            boxBlur(bmp, 2);
                            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 72, bo);
                            out[0] = "data:image/jpeg;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
                        }
                    } catch (Throwable t) { out[0] = ""; }
                    latch.countDown();
                }
            });
            try { latch.await(2, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { }
            return out[0];
        }

        /* ----- audio analysis for the background visualiser: spectrum of whatever the tablet is playing ----- */
        @JavascriptInterface
        public boolean visStart() {
            try {
                if (viz == null) {
                    viz = new android.media.audiofx.Visualizer(0); // 0 = the global output mix
                    int[] r = android.media.audiofx.Visualizer.getCaptureSizeRange();
                    viz.setCaptureSize(Math.min(1024, r[1]));
                    fftBuf = new byte[viz.getCaptureSize()];
                }
                viz.setEnabled(true);
                return true;
            } catch (Throwable t) {
                try { if (viz != null) viz.release(); } catch (Throwable e) { }
                viz = null;
                return false;
            }
        }

        @JavascriptInterface
        public void visStop() {
            try { if (viz != null) viz.setEnabled(false); } catch (Throwable t) { }
        }

        /** 48 comma-separated band levels (0-255), log-spaced from ~40 Hz to ~14 kHz; "" if unavailable */
        @JavascriptInterface
        public String visData() {
            try {
                if (viz == null || !viz.getEnabled()) return "";
                if (viz.getFft(fftBuf) != android.media.audiofx.Visualizer.SUCCESS) return "";
                int n = fftBuf.length / 2;
                double lo = 1, hi = Math.min(n - 1, 330);
                StringBuilder sb = new StringBuilder();
                for (int b = 0; b < 48; b++) {
                    int a = (int) Math.round(lo * Math.pow(hi / lo, b / 48.0));
                    int c = (int) Math.round(lo * Math.pow(hi / lo, (b + 1) / 48.0));
                    if (c <= a) c = a + 1;
                    double m = 0;
                    for (int k = a; k < c && k < n; k++) {
                        double re = fftBuf[2 * k], im = fftBuf[2 * k + 1];
                        m = Math.max(m, Math.sqrt(re * re + im * im));
                    }
                    int v = (int) Math.min(255, m * 2.4 * (1 + b * 0.012)); // slight treble lift
                    sb.append(v);
                    if (b < 47) sb.append(',');
                }
                return sb.toString();
            } catch (Throwable t) {
                return "";
            }
        }

        /** JSON {ip, pin} shown in SYSTEM so the phone app can pair */
        @JavascriptInterface
        public String remoteInfo() {
            return "{\"ip\":" + JSONObject.quote(lanAddress()) + ",\"pin\":" + JSONObject.quote(remotePin()) + "}";
        }

        @JavascriptInterface
        public String remoteNewPin() { return newPin(); }

        /** true when the page being shown was pushed over Wi-Fi instead of built into the app */
        @JavascriptInterface
        public boolean otaActive() { return MainActivity.this.otaActive(); }

        @JavascriptInterface
        public String spotifyStatus() { return spotify.connected() ? "connected" : "disconnected"; }

        @JavascriptInterface
        public void spotifyLogin() {
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(spotify.authUrl()));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Exception e) {
                        js("window.onSpotifyAuth&&window.onSpotifyAuth(" + JSONObject.quote(e.getMessage() == null ? "Could not open the browser" : e.getMessage()) + ")");
                    }
                }
            });
        }

        @JavascriptInterface
        public void spotifyLogout() { spotify.logout(); }

        @JavascriptInterface
        public String spotifyClientId() { return spotify.clientId(); }

        @JavascriptInterface
        public void spotifySetClientId(String id) { spotify.setClientId(id); }

        /** the login address (for opening on another device, e.g. a PC, when the tablet browser is too old) */
        @JavascriptInterface
        public String spotifyAuthUrl() {
            try { return spotify.authUrl(); } catch (Exception e) { return ""; }
        }

        /** async: result comes back as window.onSpotify(id, status, bodyText) */
        @JavascriptInterface
        public void spotifyCall(final int id, final String method, final String path, final String body) {
            new Thread(new Runnable() {
                public void run() {
                    String[] r = spotify.call(method, path, body);
                    js("window.onSpotify&&window.onSpotify(" + id + "," + r[0] + "," + JSONObject.quote(r[1]) + ")");
                }
            }).start();
        }

        /** screen backlight for this app only: 0..1, or a negative number for the system default */
        @JavascriptInterface
        public void setBrightness(final float v) {
            runOnUiThread(new Runnable() {
                public void run() {
                    WindowManager.LayoutParams lp = getWindow().getAttributes();
                    lp.screenBrightness = v < 0 ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE : Math.max(0.01f, Math.min(1f, v));
                    getWindow().setAttributes(lp);
                }
            });
        }

        /** the page calls this every few seconds; if it stops, the watchdog reloads the page */
        @JavascriptInterface
        public void heartbeat() { lastBeat = SystemClock.elapsedRealtime(); }

        @JavascriptInterface
        public void restartApp() { scheduleRestart(1500); android.os.Process.killProcess(android.os.Process.myPid()); }

        /** returns "current,max" for the music stream */
        @JavascriptInterface
        public String getVolume() {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            return am.getStreamVolume(AudioManager.STREAM_MUSIC) + "," + am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        }

        @JavascriptInterface
        public void setVolume(final int step) {
            runOnUiThread(new Runnable() {
                public void run() {
                    AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                    int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.max(0, Math.min(max, step)), 0);
                }
            });
        }

        @JavascriptInterface
        public String mediaAccess() { return mediaAccess; }

        @JavascriptInterface
        public void mediaCmd(final String cmd, final String arg) {
            runOnUiThread(new Runnable() {
                public void run() { mediaCommand(cmd, arg); }
            });
        }

    }

    /* ---------- web ---------- */
    private void buildWeb() {
        web = new WebView(this);
        web.setKeepScreenOn(true);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(s.getUserAgentString() + " DeskKiosk");
        web.addJavascriptInterface(new Bridge(), "Native");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
                // renderer died: rebuild the view and reload the page
                runOnUiThread(new Runnable() {
                    public void run() {
                        try { web.destroy(); } catch (Exception e) { }
                        buildWeb();
                        immersive();
                    }
                });
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsConfirm(WebView v, String url, String msg, final JsResult r) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(msg)
                        .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) { r.confirm(); }
                        })
                        .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) { r.cancel(); }
                        })
                        .setOnCancelListener(new DialogInterface.OnCancelListener() {
                            public void onCancel(DialogInterface d) { r.cancel(); }
                        })
                        .show();
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (chooser != null) chooser.onReceiveValue(null);
                chooser = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("audio/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(i, PICK);
                } catch (Exception e) {
                    chooser = null;
                    cb.onReceiveValue(null);
                }
                return true;
            }
        });
        loadedAt = SystemClock.elapsedRealtime();
        loadPage();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == PICK && chooser != null) {
            chooser.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            chooser = null;
        }
        super.onActivityResult(req, res, data);
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        immersive();
    }

    @Override
    public void onBackPressed() {
        // kiosk: ignore back
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
        immersive();
        if (!"ok".equals(mediaAccess)) startMedia(); // retry after notification access is granted
    }

    @Override
    protected void onDestroy() {
        try { if (viz != null) viz.release(); } catch (Throwable t) { }
        if (server != null) server.shutdown();
        if (wl != null && wl.isHeld()) wl.release();
        super.onDestroy();
    }
}
