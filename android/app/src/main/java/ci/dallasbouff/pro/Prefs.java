package ci.dallasbouff.pro;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/** Petit stockage local : appareil enregistré, espace choisi, alertes déjà vues. */
final class Prefs {
    private Prefs() {}

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences("dbf", Context.MODE_PRIVATE);
    }

    static String token(Context c) { return p(c).getString("token", null); }
    static String role(Context c) { return p(c).getString("role", null); }
    static String name(Context c) { return p(c).getString("name", ""); }
    static boolean fresh(Context c) { return p(c).getBoolean("fresh", false); }
    static void setFresh(Context c, boolean v) { p(c).edit().putBoolean("fresh", v).apply(); }

    /** Enregistre l'appareil. Un nouveau jeton remet à zéro les alertes déjà vues. */
    static boolean setDevice(Context c, String token, String role, String name) {
        String old = token(c);
        boolean changed = old == null || !old.equals(token);
        SharedPreferences.Editor e = p(c).edit()
                .putString("token", token).putString("role", role).putString("name", name == null ? "" : name);
        if (changed) e.putBoolean("fresh", true).remove("acked").remove("snooze");
        e.commit();
        return changed;
    }

    static void clearDevice(Context c) {
        p(c).edit().remove("token").remove("role").remove("name").remove("fresh").remove("acked").remove("snooze").commit();
    }

    static String space(Context c) { return p(c).getString("space", null); }
    static void setSpace(Context c, String s) {
        if (s == null) p(c).edit().remove("space").commit(); else p(c).edit().putString("space", s).commit();
    }

    static JSONObject json(Context c, String key) {
        try { return new JSONObject(p(c).getString(key, "{}")); } catch (Exception e) { return new JSONObject(); }
    }
    static void putJson(Context c, String key, JSONObject o) { p(c).edit().putString(key, o.toString()).apply(); }

    static long getLong(Context c, String key) { return p(c).getLong(key, 0); }
    static void putLong(Context c, String key, long v) { p(c).edit().putLong(key, v).apply(); }
}
