package ci.dallasbouff.pro;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Redémarre la surveillance après un redémarrage du téléphone ou une mise à jour de l'appli. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        Log.i(AlarmService.TAG, "BOOT " + intent.getAction());
        AlarmService.start(c, false);
    }
}
