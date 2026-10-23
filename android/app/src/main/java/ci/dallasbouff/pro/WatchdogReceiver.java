package ci.dallasbouff.pro;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Réveil de secours : relance le service s'il a été arrêté par le téléphone. */
public class WatchdogReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        Log.i(AlarmService.TAG, "WATCHDOG running=" + (AlarmService.instance != null));
        if (Prefs.token(c) == null) return;
        if (AlarmService.instance == null) AlarmService.start(c, false);
        else AlarmService.scheduleWatchdog(c);
    }
}
