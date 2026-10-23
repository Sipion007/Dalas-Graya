package ci.dallasbouff.pro;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Version de test uniquement. */
public class TestReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        Log.i(AlarmService.TAG, "TEST receiver " + i.getAction());
        if (AlarmService.ACT_ACK.equals(i.getAction())) AlarmService.send(c, AlarmService.ACT_ACK);
        else if (AlarmService.ACT_TEST.equals(i.getAction())) AlarmService.send(c, AlarmService.ACT_TEST);
    }
}
