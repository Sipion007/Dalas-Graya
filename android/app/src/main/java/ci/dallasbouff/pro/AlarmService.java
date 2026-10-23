package ci.dallasbouff.pro;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.AssetFileDescriptor;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Service permanent : interroge le serveur toutes les 15 à 20 secondes et fait sonner
 * une vraie alarme (canal « réveil ») tant qu'une commande attend, même téléphone verrouillé.
 */
public class AlarmService extends Service {
    static final String TAG = "DBF";
    static final String ACT_ACK = "ci.dallasbouff.pro.ACK";
    static final String ACT_POKE = "ci.dallasbouff.pro.POKE";
    static final String ACT_STOP = "ci.dallasbouff.pro.STOP";
    static final String ACT_TEST = "ci.dallasbouff.pro.TEST";
    static final String EXTRA_FROM_UI = "fromUi";

    static final long SNOOZE_MS = 90_000;
    static final long POLL_MS = 20_000;
    static final long POLL_BUSY_MS = 15_000;
    static final int ID_SERVICE = 1, ID_ALARM = 2, ID_INFO = 3;
    static final String CH_SERVICE = "dbf_service", CH_ALARM = "dbf_alarm", CH_INFO = "dbf_info";

    static volatile AlarmService instance;
    static volatile boolean appVisible;

    static final class Item {
        String k, kind, t, l;
        boolean rep;
        int age;
    }

    private HandlerThread thread;
    private Handler h;
    private PowerManager.WakeLock wakeLock;
    private MediaPlayer player;
    private Vibrator vibrator;
    private int savedVolume = -1;

    private volatile boolean ringing;
    private final List<Item> current = new ArrayList<>();
    private volatile List<Item> ringItems = new ArrayList<>();
    private Item testItem;
    private JSONObject acked, snooze;
    private volatile int failures;
    volatile long lastOk;
    private boolean loopScheduled;

    private LocationManager locationManager;
    private LocationListener locationListener;
    private long lastLocationSent;
    private boolean locationTyped;

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            loopScheduled = false;
            pollOnce();
            scheduleLoop(nextDelay());
        }
    };
    private final Runnable reevaluate = this::evaluate;

    // ------------------------------------------------------------------ cycle de vie

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        createChannels(this);
        thread = new HandlerThread("dbf-alarm");
        thread.start();
        h = new Handler(thread.getLooper());
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dallasbouff:alarm");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
        acked = Prefs.json(this, "acked");
        snooze = Prefs.json(this, "snooze");
        Log.i(TAG, "SERVICE create");
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        boolean fromUi = intent != null && intent.getBooleanExtra(EXTRA_FROM_UI, false);
        goForeground(fromUi || appVisible);
        if (Prefs.token(this) == null) {
            Log.i(TAG, "SERVICE no token, stop");
            shutdown();
            return START_NOT_STICKY;
        }
        String action = intent == null ? null : intent.getAction();
        if (ACT_STOP.equals(action)) { shutdown(); return START_NOT_STICKY; }
        if (ACT_ACK.equals(action)) h.post(this::ack);
        else if (ACT_TEST.equals(action)) h.postDelayed(this::startTest, 8000);
        if (ACT_POKE.equals(action) || !loopScheduled) { h.removeCallbacks(loop); loopScheduled = true; h.post(loop); }
        h.post(this::setupLocation);
        scheduleWatchdog(this);
        return START_STICKY;
    }

    @Override public void onDestroy() {
        Log.i(TAG, "SERVICE destroy");
        stopRing();
        stopLocation();
        if (h != null) h.removeCallbacksAndMessages(null);
        if (thread != null) thread.quitSafely();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void shutdown() {
        h.post(() -> {
            stopRing();
            stopLocation();
            cancelWatchdog(this);
            stopForeground(true);
            stopSelf();
        });
    }

    // ------------------------------------------------------------------ premier plan

    private boolean isDriver() { return "driver".equals(Prefs.role(this)); }

    private boolean hasLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void goForeground(boolean fromUi) {
        Notification n = serviceNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
            // Le type « position » ne peut être demandé que quand l'appli est à l'écran ;
            // sinon on garde celui obtenu précédemment.
            boolean wantLoc = isDriver() && hasLocation() && (fromUi ? true : locationTyped);
            if (wantLoc) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            try {
                startForeground(ID_SERVICE, n, type);
                locationTyped = wantLoc;
            } catch (Exception e) {
                Log.w(TAG, "FGS location refused: " + e);
                startForeground(ID_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                locationTyped = false;
            }
        } else {
            startForeground(ID_SERVICE, n);
            locationTyped = true;
        }
    }

    private Notification serviceNotification() {
        String who = Prefs.name(this);
        String title = "Dallas Bouff' Pro est en service" + (who.isEmpty() ? "" : " · " + who);
        String text = failures >= 3
                ? "Pas de connexion internet. Les alertes reprendront dès le retour du réseau."
                : "Vous serez alerté même téléphone en poche.";
        return builder(this, CH_SERVICE)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setOngoing(true)
                .setShowWhen(false)
                .setColor(0xFFF5C04A)
                .setContentIntent(openAppIntent(this))
                .build();
    }

    private void refreshServiceNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(ID_SERVICE, serviceNotification());
    }

    // ------------------------------------------------------------------ interrogation du serveur

    private void scheduleLoop(long delay) {
        h.removeCallbacks(loop);
        loopScheduled = true;
        h.postDelayed(loop, delay);
    }

    private long nextDelay() {
        if (failures > 0) return POLL_BUSY_MS;
        long d = (ringing || !current.isEmpty()) ? POLL_BUSY_MS : POLL_MS;
        long now = System.currentTimeMillis();
        for (Item it : current) {
            long until = snooze.optLong(it.k, 0);
            if (until > now) d = Math.min(d, until - now + 300);
        }
        return Math.max(1000, d);
    }

    private void pollOnce() {
        String tok = Prefs.token(this);
        if (tok == null) { shutdown(); return; }
        try {
            JSONObject r = Api.rpcObject("alarm_poll", new JSONObject().put("p_token", tok));
            if (!r.optBoolean("ok", false)) {
                Log.i(TAG, "POLL token rejected");
                Prefs.clearDevice(this);
                showInfo("Vous avez été déconnecté",
                        "Ouvrez Dallas Bouff' Pro et reconnectez-vous pour recevoir à nouveau les alertes.");
                shutdown();
                return;
            }
            String name = r.optString("name", "");
            if (!name.equals(Prefs.name(this))) {
                Prefs.setDevice(this, tok, r.optString("role", Prefs.role(this)), name);
                refreshServiceNotification();
            }
            JSONArray arr = r.optJSONArray("items");
            current.clear();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Item it = new Item();
                    it.k = o.getString("k");
                    it.kind = o.optString("kind", "order");
                    it.rep = o.optBoolean("rep", false);
                    it.t = o.optString("t", "ALERTE");
                    it.l = o.optString("l", "");
                    it.age = o.optInt("age", 0);
                    current.add(it);
                }
            }
            boolean wasFailing = failures >= 3;
            failures = 0;
            lastOk = System.currentTimeMillis();
            if (wasFailing) refreshServiceNotification();
            if (Prefs.fresh(this)) {
                long now = System.currentTimeMillis();
                for (Item it : current) if (!it.rep) acked.put(it.k, now);
                Prefs.setFresh(this, false);
                saveState();
            }
            prune();
            Log.i(TAG, "POLL ok items=" + current.size());
            evaluate();
        } catch (Exception e) {
            failures++;
            Log.w(TAG, "POLL fail #" + failures + " " + e);
            if (failures == 3) refreshServiceNotification();
        }
    }

    /** Oublie les alertes vues depuis plus d'un jour et qui n'existent plus. */
    private void prune() {
        long now = System.currentTimeMillis();
        List<String> keys = new ArrayList<>();
        for (Item it : current) keys.add(it.k);
        boolean changed = false;
        for (Iterator<String> i = acked.keys(); i.hasNext(); ) {
            String k = i.next();
            if (!keys.contains(k) && now - acked.optLong(k, 0) > 24 * 3600_000L) { i.remove(); changed = true; }
        }
        for (Iterator<String> i = snooze.keys(); i.hasNext(); ) {
            String k = i.next();
            if (!keys.contains(k) && snooze.optLong(k, 0) < now) { i.remove(); changed = true; }
        }
        if (changed) saveState();
    }

    private void saveState() {
        Prefs.putJson(this, "acked", acked);
        Prefs.putJson(this, "snooze", snooze);
    }

    private static int maxAgeMinutes(String kind) {
        return "available".equals(kind) ? 60 : 45;
    }

    /** Décide s'il faut sonner, à partir de la dernière réponse du serveur. */
    private void evaluate() {
        h.removeCallbacks(reevaluate);
        long now = System.currentTimeMillis();
        List<Item> ring = new ArrayList<>();
        if (testItem != null) ring.add(testItem);

        if (appVisible) {
            // L'appli est à l'écran : c'est elle qui sonne. On note ce qui a été vu.
            try {
                for (Item it : current) {
                    if (it.rep) snooze.put(it.k, now + SNOOZE_MS);
                    else acked.put(it.k, now);
                }
            } catch (Exception ignored) {}
            saveState();
            if (ring.isEmpty()) {
                if (ringing) Log.i(TAG, "RING stop (app visible)");
                stopRing();
                return;
            }
        } else {
            for (Item it : current) {
                if (it.rep) {
                    if (it.age >= maxAgeMinutes(it.kind)) continue;
                    if (snooze.optLong(it.k, 0) > now) continue;
                    ring.add(it);
                } else if (!acked.has(it.k)) {
                    ring.add(it);
                }
            }
        }
        if (!ring.isEmpty()) startRing(ring);
        else if (ringing) { Log.i(TAG, "RING stop (nothing pending)"); stopRing(); }
    }

    private void ack() {
        long now = System.currentTimeMillis();
        try {
            for (Item it : ringItems) {
                if (it == testItem) continue;
                if (it.rep) snooze.put(it.k, now + SNOOZE_MS);
                else acked.put(it.k, now);
            }
        } catch (Exception ignored) {}
        testItem = null;
        saveState();
        Log.i(TAG, "RING ack");
        stopRing();
        scheduleLoop(nextDelay());
    }

    private void startTest() {
        Item it = new Item();
        it.k = "test";
        it.kind = "test";
        it.rep = false;
        it.t = "TEST DE L'ALARME";
        it.l = "Si vous entendez cette sonnerie, l'alarme fonctionne même téléphone verrouillé.";
        testItem = it;
        evaluate();
        h.postDelayed(() -> {
            if (testItem == it) { testItem = null; evaluate(); }
        }, 30_000);
    }

    // ------------------------------------------------------------------ sonnerie

    List<Item> ringingItems() { return ringItems; }
    boolean isRinging() { return ringing; }

    static String titleFor(List<Item> items) {
        if (items.isEmpty()) return "ALERTE";
        Item first = items.get(0);
        int same = 0;
        for (Item it : items) if (it.kind.equals(first.kind)) same++;
        if (same > 1 && "order".equals(first.kind)) return same + " COMMANDES À ACCEPTER";
        if (same > 1 && "available".equals(first.kind)) return same + " LIVRAISONS DISPONIBLES";
        return first.t;
    }

    static boolean anyRepeating(List<Item> items) {
        for (Item it : items) if (it.rep) return true;
        return false;
    }

    private void startRing(List<Item> items) {
        ringItems = items;
        boolean already = ringing;
        ringing = true;
        postAlarmNotification(items);
        AlarmActivity.refreshIfOpen();
        if (already) return;
        Log.i(TAG, "RING start " + titleFor(items) + " n=" + items.size());
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        try {
            savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            if (savedVolume < max) am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0);
        } catch (Exception e) {
            savedVolume = -1;
        }
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            AssetFileDescriptor afd = getResources().openRawResourceFd(R.raw.alarm);
            player.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            player.setLooping(true);
            player.prepare();
            player.start();
        } catch (Exception e) {
            Log.w(TAG, "player error " + e);
        }
        try {
            vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            long[] pattern = {0, 700, 300, 700, 1300};
            if (vibrator != null) {
                if (Build.VERSION.SDK_INT >= 26) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0),
                            new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
                } else {
                    vibrator.vibrate(pattern, 0, new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build());
                }
            }
        } catch (Exception ignored) {}
    }

    private void stopRing() {
        boolean was = ringing;
        ringing = false;
        ringItems = new ArrayList<>();
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
        if (vibrator != null) { try { vibrator.cancel(); } catch (Exception ignored) {} }
        if (was && savedVolume >= 0) {
            try { ((AudioManager) getSystemService(AUDIO_SERVICE)).setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0); } catch (Exception ignored) {}
            savedVolume = -1;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(ID_ALARM);
        AlarmActivity.closeIfOpen();
    }

    private void postAlarmNotification(List<Item> items) {
        String title = titleFor(items);
        Notification.InboxStyle style = new Notification.InboxStyle().setBigContentTitle(title);
        int n = 0;
        for (Item it : items) { if (n++ < 5) style.addLine(it.l); }
        boolean rep = anyRepeating(items);
        style.setSummaryText(rep ? "Acceptez dans l'appli pour arrêter l'alarme" : "Touchez J'ai vu pour arrêter");
        Intent full = new Intent(this, AlarmActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        PendingIntent fullPi = PendingIntent.getActivity(this, 20, full, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notif = builder(this, CH_ALARM)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(items.get(0).l)
                .setStyle(style)
                .setCategory(Notification.CATEGORY_ALARM)
                .setPriority(Notification.PRIORITY_MAX)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setColor(0xFFF5C04A)
                .setContentIntent(openAppIntent(this))
                .setFullScreenIntent(fullPi, true)
                .addAction(new Notification.Action.Builder(null, rep ? "J'ai vu (1 min 30)" : "J'ai vu", ackIntent(this)).build())
                .addAction(new Notification.Action.Builder(null, "Ouvrir l'appli", openAppIntent(this)).build())
                .build();
        notif.flags |= Notification.FLAG_INSISTENT;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(ID_ALARM, notif);
    }

    private void showInfo(String title, String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.notify(ID_INFO, builder(this, CH_INFO)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setColor(0xFFF5C04A)
                .setContentIntent(openAppIntent(this))
                .build());
    }

    // ------------------------------------------------------------------ position du livreur

    private void setupLocation() {
        if (!isDriver() || !hasLocation() || !locationTyped) { stopLocation(); return; }
        if (locationListener != null) return;
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (locationManager == null) return;
        locationListener = new LocationListener() {
            @Override public void onLocationChanged(Location loc) { sendLocation(loc); }
            @Override public void onStatusChanged(String p, int s, Bundle b) {}
            @Override public void onProviderEnabled(String p) {}
            @Override public void onProviderDisabled(String p) {}
        };
        try {
            for (String prov : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                if (locationManager.getAllProviders().contains(prov)) {
                    locationManager.requestLocationUpdates(prov, 20_000, 15, locationListener, thread.getLooper());
                }
            }
            Log.i(TAG, "LOCATION on");
        } catch (SecurityException e) {
            Log.w(TAG, "LOCATION refused " + e);
            locationListener = null;
        }
    }

    private void stopLocation() {
        if (locationManager != null && locationListener != null) {
            try { locationManager.removeUpdates(locationListener); } catch (Exception ignored) {}
        }
        locationListener = null;
    }

    private void sendLocation(Location loc) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastLocationSent < 25_000) return;
        lastLocationSent = now;
        String tok = Prefs.token(this);
        if (tok == null) return;
        try {
            Api.rpc("alarm_location", new JSONObject().put("p_token", tok).put("p_lat", loc.getLatitude()).put("p_lng", loc.getLongitude()));
        } catch (Exception e) {
            Log.w(TAG, "LOCATION send fail " + e);
        }
    }

    // ------------------------------------------------------------------ outils partagés

    static Notification.Builder builder(Context c, String channel) {
        return Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, channel) : new Notification.Builder(c);
    }

    static void createChannels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel s = new NotificationChannel(CH_SERVICE, "Service en cours", NotificationManager.IMPORTANCE_LOW);
        s.setDescription("Indique que l'appli surveille les nouvelles commandes.");
        s.setShowBadge(false);
        s.setSound(null, null);
        nm.createNotificationChannel(s);
        NotificationChannel a = new NotificationChannel(CH_ALARM, "Alarme des commandes", NotificationManager.IMPORTANCE_HIGH);
        a.setDescription("Sonne comme un réveil tant qu'une commande attend.");
        a.setSound(null, null);
        a.enableVibration(false);
        a.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        a.setBypassDnd(true);
        nm.createNotificationChannel(a);
        NotificationChannel i = new NotificationChannel(CH_INFO, "Informations", NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(i);
    }

    static PendingIntent openAppIntent(Context c) {
        Intent i = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, 30, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static PendingIntent ackIntent(Context c) {
        Intent i = new Intent(c, AlarmService.class).setAction(ACT_ACK);
        return PendingIntent.getService(c, 10, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void send(Context c, String action) {
        if (instance == null) return;
        try { c.startService(new Intent(c, AlarmService.class).setAction(action)); } catch (Exception e) { Log.w(TAG, "send " + e); }
    }

    static void start(Context c, boolean fromUi) {
        if (Prefs.token(c) == null) return;
        Intent i = new Intent(c, AlarmService.class).putExtra(EXTRA_FROM_UI, fromUi);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception e) {
            Log.w(TAG, "start refused " + e);
        }
    }

    /** Réveil de secours toutes les 15 minutes : relance le service si le téléphone l'a arrêté. */
    static void scheduleWatchdog(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = PendingIntent.getBroadcast(c, 40, new Intent(c, WatchdogReceiver.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        long at = System.currentTimeMillis() + 15 * 60_000L;
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (Exception e) {
            try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi); } catch (Exception ignored) {}
        }
    }

    static void cancelWatchdog(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(ALARM_SERVICE);
        if (am == null) return;
        am.cancel(PendingIntent.getBroadcast(c, 40, new Intent(c, WatchdogReceiver.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
    }
}
