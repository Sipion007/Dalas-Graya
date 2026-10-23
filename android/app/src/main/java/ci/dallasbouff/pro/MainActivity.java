package ci.dallasbouff.pro;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

/** L'espace restaurant ou livreur du site, dans une appli qui sait sonner téléphone en poche. */
public class MainActivity extends Activity {
    static final String SITE = "https://dalas-graya.vercel.app/";
    static final String HOST = "dalas-graya.vercel.app";
    private static final int RQ_FILE = 11, RQ_NOTIF = 21, RQ_LOC = 22, RQ_GEO = 23;

    private FrameLayout root;
    private WebView web;
    private ProgressBar progress;
    private ValueCallback<Uri[]> fileCallback;
    private GeolocationPermissions.Callback geoCallback;
    private String geoOrigin;
    private boolean permissionFlowDone;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        AlarmService.createChannels(this);
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        setContentView(root);
        handleTestIntent(getIntent());
        String space = Prefs.space(this);
        if (space == null) showChooser(); else showWeb(space);
        if (Prefs.token(this) != null) AlarmService.start(this, true);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleTestIntent(intent);
    }

    /** Uniquement dans la version de test : permet aux tests automatiques de régler l'appareil. */
    private void handleTestIntent(Intent i) {
        if (!BuildConfig.DEBUG || i == null) return;
        String tok = i.getStringExtra("dbf_test_token");
        if (tok != null) {
            String role = i.getStringExtra("dbf_test_role");
            Prefs.setDevice(this, tok, role == null ? "test" : role, "Test");
            AlarmService.start(this, true);
        }
        String space = i.getStringExtra("dbf_space");
        if (space != null) { Prefs.setSpace(this, space); if (root != null) showWeb(space); }
    }

    @Override protected void onResume() {
        super.onResume();
        AlarmService.appVisible = true;
        AlarmService.send(this, AlarmService.ACT_POKE);
        if (web != null) {
            web.onResume();
            web.evaluateJavascript("window.dispatchEvent(new Event('dbf-native-resume'))", null);
        }
    }

    @Override protected void onPause() {
        AlarmService.appVisible = false;
        AlarmService.send(this, AlarmService.ACT_POKE);
        if (web != null) web.onPause();
        super.onPause();
    }

    @Override public void onBackPressed() {
        if (web != null && web.getVisibility() == View.VISIBLE && web.canGoBack()) web.goBack();
        else moveTaskToBack(true);
    }

    // ------------------------------------------------------------------ choix de l'espace

    private void showChooser() {
        destroyWeb();
        root.removeAllViews();
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Ui.dp(this, 24);
        col.setPadding(pad, Ui.dp(this, 56), pad, Ui.dp(this, 32));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo);
        col.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 132), Ui.dp(this, 132)));

        TextView k = Ui.text(this, "ESPACE PARTENAIRES", 13, Ui.GOLD, true);
        k.setLetterSpacing(0.18f);
        LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(-2, -2);
        kp.topMargin = Ui.dp(this, 26);
        col.addView(k, kp);

        TextView t = Ui.text(this, "Dallas Bouff' Pro", 30, Ui.FG, true);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.topMargin = Ui.dp(this, 8);
        col.addView(t, tp);

        TextView s = Ui.text(this, "L'alarme sonne même quand votre téléphone est verrouillé, dans votre poche. Aucune commande ne vous échappe.", 15.5f, Ui.DIM, false);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = Ui.dp(this, 12);
        col.addView(s, sp);

        View spacer = new View(this);
        col.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        android.widget.Button r = Ui.button(this, "Je suis restaurateur", true);
        r.setOnClickListener(v -> { Prefs.setSpace(this, "restaurant"); showWeb("restaurant"); });
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.topMargin = Ui.dp(this, 32);
        col.addView(r, rp);

        android.widget.Button d = Ui.button(this, "Je suis livreur", false);
        d.setOnClickListener(v -> { Prefs.setSpace(this, "driver"); showWeb("driver"); });
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(-1, -2);
        dp.topMargin = Ui.dp(this, 12);
        col.addView(d, dp);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.addView(col, new ScrollView.LayoutParams(-1, -1));
        root.addView(sv, new FrameLayout.LayoutParams(-1, -1));
    }

    // ------------------------------------------------------------------ site dans l'appli

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void showWeb(String space) {
        destroyWeb();
        root.removeAllViews();
        web = new WebView(this);
        web.setBackgroundColor(Ui.BG);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setGeolocationEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        ws.setSupportMultipleWindows(false);
        ws.setJavaScriptCanOpenWindowsAutomatically(false);
        ws.setUserAgentString(ws.getUserAgentString() + " DallasBouffPro/" + BuildConfig.VERSION_NAME);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.addJavascriptInterface(new Bridge(), "DBFAndroid");
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(-1, Ui.dp(this, 4));
        pp.gravity = Gravity.TOP;
        root.addView(progress, pp);

        web.loadUrl(startUrl(space));
    }

    private static String startUrl(String space) {
        return SITE + ("driver".equals(space) ? "driver.html" : "dashboard.html");
    }

    private void destroyWeb() {
        if (web != null) {
            ViewGroup p = (ViewGroup) web.getParent();
            if (p != null) p.removeView(web);
            web.destroy();
            web = null;
        }
    }

    private class Client extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
            return handleUrl(req.getUrl());
        }

        @SuppressWarnings("deprecation")
        @Override public boolean shouldOverrideUrlLoading(WebView v, String url) {
            return handleUrl(Uri.parse(url));
        }

        @Override public void onPageStarted(WebView v, String url, Bitmap favicon) {
            if (progress != null) progress.setVisibility(View.VISIBLE);
        }

        @Override public void onPageFinished(WebView v, String url) {
            Log.i(AlarmService.TAG, "PAGE finished " + url);
            if (progress != null) progress.setVisibility(View.GONE);
        }

        @Override public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
            if (req.isForMainFrame()) showOffline(v, req.getUrl().toString());
        }
    }

    /** Les pages du site restent dans l'appli ; appels, WhatsApp et cartes s'ouvrent dans l'appli prévue. */
    private boolean handleUrl(Uri u) {
        String scheme = u.getScheme() == null ? "" : u.getScheme();
        if (("https".equals(scheme) || "http".equals(scheme)) && HOST.equalsIgnoreCase(u.getHost())) return false;
        try {
            Intent i;
            if ("intent".equals(scheme)) i = Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME);
            else i = new Intent(Intent.ACTION_VIEW, u);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Log.w(AlarmService.TAG, "no app for " + u);
        }
        return true;
    }

    private void showOffline(WebView v, String url) {
        String safe = url.replace("\"", "%22");
        String html = "<!doctype html><html><head><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<style>body{margin:0;background:#0A0A0A;color:#F4F2EC;font-family:sans-serif;display:flex;min-height:100vh;align-items:center;justify-content:center;text-align:center;padding:24px;box-sizing:border-box}"
                + "h1{font-size:22px;margin:0 0 10px}p{color:#9A978F;margin:0 0 24px;line-height:1.5}"
                + "a{display:inline-block;background:#D4A537;color:#0A0A0A;font-weight:700;padding:16px 28px;border-radius:14px;text-decoration:none}</style></head>"
                + "<body><div><h1>Pas de connexion internet</h1><p>Vérifiez vos données mobiles ou le Wi-Fi.<br>Les alertes reprendront dès le retour du réseau.</p>"
                + "<a href=\"" + safe + "\">Réessayer</a></div></body></html>";
        v.loadDataWithBaseURL(SITE, html, "text/html", "utf-8", url);
    }

    private class Chrome extends WebChromeClient {
        @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
            if (hasLocation()) { cb.invoke(origin, true, false); return; }
            geoOrigin = origin;
            geoCallback = cb;
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, RQ_GEO);
        }

        @Override public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = cb;
            try {
                Intent i = params.createIntent();
                i.addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(Intent.createChooser(i, "Choisir une photo"), RQ_FILE);
            } catch (ActivityNotFoundException e) {
                fileCallback = null;
                cb.onReceiveValue(null);
                return false;
            }
            return true;
        }
    }

    @Override protected void onActivityResult(int rq, int res, Intent data) {
        super.onActivityResult(rq, res, data);
        if (rq == RQ_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            fileCallback = null;
        }
    }

    // ------------------------------------------------------------------ autorisations

    private boolean hasLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean notificationsOk() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        return nm == null || nm.areNotificationsEnabled();
    }

    private boolean batteryOk() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm == null || pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private boolean fullScreenOk() {
        if (Build.VERSION.SDK_INT < 34) return true;
        NotificationManager nm = getSystemService(NotificationManager.class);
        return nm == null || nm.canUseFullScreenIntent();
    }

    /** Demande, l'une après l'autre, les autorisations nécessaires pour sonner téléphone en poche. */
    private void runPermissionFlow(int step) {
        if (step <= 1 && Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            markNotifAsked();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, RQ_NOTIF);
            return;
        }
        if (step <= 2 && "driver".equals(Prefs.role(this)) && !hasLocation()) {
            markLocationAsked();
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, RQ_LOC);
            return;
        }
        if (step <= 3 && !batteryOk()) {
            long last = Prefs.getLong(this, "batteryAsked");
            if (System.currentTimeMillis() - last > 12 * 3600_000L) {
                Prefs.putLong(this, "batteryAsked", System.currentTimeMillis());
                openSetting("battery");
            }
        }
        permissionFlowDone = true;
        AlarmService.start(this, true);
    }

    @Override public void onRequestPermissionsResult(int rq, String[] perms, int[] res) {
        super.onRequestPermissionsResult(rq, perms, res);
        if (rq == RQ_GEO && geoCallback != null) {
            geoCallback.invoke(geoOrigin, hasLocation(), false);
            geoCallback = null;
            if (hasLocation()) AlarmService.start(this, true);
        } else if (rq == RQ_NOTIF) {
            runPermissionFlow(2);
        } else if (rq == RQ_LOC) {
            runPermissionFlow(3);
        }
    }

    private void openSetting(String which) {
        Intent i;
        Uri pkg = Uri.parse("package:" + getPackageName());
        switch (which) {
            case "battery":
                i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg);
                break;
            case "fullscreen":
                i = Build.VERSION.SDK_INT >= 34
                        ? new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg)
                        : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg);
                break;
            case "notif":
                if (Build.VERSION.SDK_INT >= 26) {
                    i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                } else {
                    i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg);
                }
                break;
            default:
                i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg);
        }
        try {
            startActivity(i);
        } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)); } catch (Exception ignored) {}
        }
    }

    // ------------------------------------------------------------------ pont avec la page web

    private class Bridge {
        @JavascriptInterface public String getToken() {
            String t = Prefs.token(MainActivity.this);
            return t == null ? "" : t;
        }

        @JavascriptInterface public void setDevice(String token, String role, String name) {
            if (token == null || token.isEmpty()) return;
            if (!"restaurant".equals(role) && !"driver".equals(role)) return;
            Prefs.setDevice(MainActivity.this, token, role, name);
            Log.i(AlarmService.TAG, "BRIDGE setDevice role=" + role);
            runOnUiThread(() -> {
                AlarmService.start(MainActivity.this, true);
                if (!permissionFlowDone) runPermissionFlow(1);
            });
        }

        @JavascriptInterface public void clearDevice() {
            Log.i(AlarmService.TAG, "BRIDGE clearDevice");
            Prefs.clearDevice(MainActivity.this);
            AlarmService.send(MainActivity.this, AlarmService.ACT_STOP);
        }

        @JavascriptInterface public String status() {
            JSONObject o = new JSONObject();
            try {
                AlarmService s = AlarmService.instance;
                o.put("running", s != null && Prefs.token(MainActivity.this) != null);
                o.put("connected", s != null && s.lastOk > 0 && System.currentTimeMillis() - s.lastOk < 90_000);
                o.put("notif", notificationsOk());
                o.put("battery", batteryOk());
                o.put("fullscreen", fullScreenOk());
                o.put("location", !"driver".equals(Prefs.role(MainActivity.this)) || hasLocation());
                o.put("role", Prefs.role(MainActivity.this) == null ? "" : Prefs.role(MainActivity.this));
                o.put("version", BuildConfig.VERSION_NAME);
            } catch (Exception ignored) {}
            return o.toString();
        }

        @JavascriptInterface public void fix(String which) {
            runOnUiThread(() -> {
                if ("location".equals(which)) {
                    if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) || !hasLocationAskedBefore()) {
                        markLocationAsked();
                        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, RQ_LOC);
                    } else openSetting("app");
                } else if ("notif".equals(which)) {
                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                            && (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) || !notifAskedBefore())) {
                        markNotifAsked();
                        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, RQ_NOTIF);
                    } else openSetting("notif");
                } else {
                    openSetting(which);
                }
            });
        }

        @JavascriptInterface public void testAlarm() {
            runOnUiThread(() -> {
                if (AlarmService.instance == null) AlarmService.start(MainActivity.this, true);
                root.postDelayed(() -> AlarmService.send(MainActivity.this, AlarmService.ACT_TEST), 600);
            });
        }

        @JavascriptInterface public void switchSpace() {
            runOnUiThread(() -> {
                final String tok = Prefs.token(MainActivity.this);
                Prefs.clearDevice(MainActivity.this);
                AlarmService.send(MainActivity.this, AlarmService.ACT_STOP);
                if (tok != null) new Thread(() -> {
                    try { Api.rpc("unregister_alarm_device", new JSONObject().put("p_token", tok)); } catch (Exception ignored) {}
                }).start();
                Prefs.setSpace(MainActivity.this, null);
                showChooser();
            });
        }
    }

    private boolean hasLocationAskedBefore() { return Prefs.getLong(this, "locAsked") > 0; }
    private void markLocationAsked() { Prefs.putLong(this, "locAsked", System.currentTimeMillis()); }
    private boolean notifAskedBefore() { return Prefs.getLong(this, "notifAsked") > 0; }
    private void markNotifAsked() { Prefs.putLong(this, "notifAsked", System.currentTimeMillis()); }
}
