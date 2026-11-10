package com.deskdisplay.remote;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Desk Remote: finds the Desk Display on the Wi-Fi, pairs with its PIN, then shows its control page. */
public class MainActivity extends Activity {
    private WebView web;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("remote", MODE_PRIVATE);
        getWindow().setStatusBarColor(0xFF000000);
        getWindow().setNavigationBarColor(0xFF000000);
        web = new WebView(this);
        web.setBackgroundColor(0xFF000000);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "App");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                // the display is off, moved, or not on this Wi-Fi: go back to the pairing screen
                if (r.isForMainFrame() && r.getUrl().toString().startsWith("http")) {
                    v.loadUrl("file:///android_asset/connect.html?err=1");
                }
            }
        });
        web.loadUrl("file:///android_asset/connect.html");
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }

    private void js(final String code) {
        runOnUiThread(new Runnable() {
            public void run() { web.evaluateJavascript(code, null); }
        });
    }

    private static String localIp() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback() || !ni.getName().startsWith("wlan")) continue;
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (a instanceof Inet4Address) return a.getHostAddress();
                }
            }
        } catch (Exception e) { }
        return null;
    }

    private static String get(String url, String pin, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs + 800);
        if (pin != null) c.setRequestProperty("X-Pin", pin);
        int code = c.getResponseCode();
        if (code != 200) throw new Exception("HTTP " + code);
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        return sb.toString();
    }

    private class Bridge {
        @JavascriptInterface
        public String saved() {
            String ip = prefs.getString("ip", ""), pin = prefs.getString("pin", "");
            return ip.length() > 0 && pin.length() > 0 ? ip + "|" + pin : "";
        }

        @JavascriptInterface
        public void save(String ip, String pin) {
            prefs.edit().putString("ip", ip).putString("pin", pin).apply();
        }

        @JavascriptInterface
        public void forget() {
            prefs.edit().clear().apply();
            runOnUiThread(new Runnable() {
                public void run() { web.loadUrl("file:///android_asset/connect.html?forget=1"); }
            });
        }

        @JavascriptInterface
        public void open(final String ip, final String pin) {
            runOnUiThread(new Runnable() {
                public void run() { web.loadUrl("http://" + ip + ":8080/remote?pin=" + pin); }
            });
        }

        /** checks the PIN against the display; answer arrives as window.onTest(ok, message) */
        @JavascriptInterface
        public void test(final String ip, final String pin) {
            new Thread(new Runnable() {
                public void run() {
                    try {
                        get("http://" + ip + ":8080/api/hello", pin, 2500);
                        js("window.onTest&&window.onTest(true,'ok')");
                    } catch (Exception e) {
                        String m = String.valueOf(e.getMessage());
                        js("window.onTest&&window.onTest(false," + JSONObject.quote(m.contains("401") ? "Wrong PIN" : m.contains("429") ? "Too many tries, wait a minute" : "Could not reach the display") + ")");
                    }
                }
            }).start();
        }

        /** looks for displays on the phone's Wi-Fi network; answer arrives as window.onScan([ "ip|name", ... ]) */
        @JavascriptInterface
        public void scan() {
            new Thread(new Runnable() {
                public void run() {
                    String mine = localIp();
                    if (mine == null) { js("window.onScan([])"); return; }
                    final String prefix = mine.substring(0, mine.lastIndexOf('.') + 1);
                    final List<String> found = Collections.synchronizedList(new ArrayList<String>());
                    ExecutorService ex = Executors.newFixedThreadPool(64);
                    for (int i = 1; i < 255; i++) {
                        final String ip = prefix + i;
                        if (ip.equals(mine)) continue;
                        ex.submit(new Runnable() {
                            public void run() {
                                try {
                                    String t = get("http://" + ip + ":8080/ping", null, 700);
                                    if (t.contains("deskdisplay")) {
                                        String name = new JSONObject(t).optString("name", "Desk Display");
                                        found.add(ip + "|" + name);
                                    }
                                } catch (Exception e) { }
                            }
                        });
                    }
                    ex.shutdown();
                    try { ex.awaitTermination(20, TimeUnit.SECONDS); } catch (InterruptedException e) { }
                    js("window.onScan(" + new JSONArray(found).toString() + ")");
                }
            }).start();
        }
    }
}
