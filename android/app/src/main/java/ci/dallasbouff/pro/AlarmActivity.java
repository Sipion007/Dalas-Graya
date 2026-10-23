package ci.dallasbouff.pro;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** Écran plein qui s'affiche par-dessus l'écran verrouillé pendant que l'alarme sonne. */
public class AlarmActivity extends Activity {
    private static volatile AlarmActivity open;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private TextView title, kicker;
    private LinearLayout lines;
    private android.widget.Button ackBtn;

    static void refreshIfOpen() { MAIN.post(() -> { AlarmActivity a = open; if (a != null) a.render(); }); }
    static void closeIfOpen() { MAIN.post(() -> { AlarmActivity a = open; if (a != null) a.finish(); }); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Ui.dp(this, 24);
        col.setPadding(pad, Ui.dp(this, 48), pad, Ui.dp(this, 32));
        col.setBackgroundColor(Ui.BG);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.logo);
        col.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 104), Ui.dp(this, 104)));

        kicker = Ui.text(this, "DALLAS BOUFF' PRO", 13, Ui.GOLD, true);
        kicker.setLetterSpacing(0.18f);
        LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(-2, -2);
        kp.topMargin = Ui.dp(this, 22);
        col.addView(kicker, kp);

        title = Ui.text(this, "", 32, Ui.FG, true);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.topMargin = Ui.dp(this, 10);
        col.addView(title, tp);

        lines = new LinearLayout(this);
        lines.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(this, 22);
        col.addView(lines, lp);

        View spacer = new View(this);
        col.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        android.widget.Button openBtn = Ui.button(this, "Ouvrir et accepter", true);
        openBtn.setOnClickListener(v -> {
            KeyguardManager km = getSystemService(KeyguardManager.class);
            if (Build.VERSION.SDK_INT >= 26 && km != null && km.isKeyguardLocked()) {
                km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                    @Override public void onDismissSucceeded() { openMain(); }
                });
            } else {
                openMain();
            }
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.topMargin = Ui.dp(this, 24);
        col.addView(openBtn, bp);

        ackBtn = Ui.button(this, "J'ai vu", false);
        ackBtn.setOnClickListener(v -> {
            AlarmService.send(this, AlarmService.ACT_ACK);
            finish();
        });
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, -2);
        ap.topMargin = Ui.dp(this, 12);
        col.addView(ackBtn, ap);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackgroundColor(Ui.BG);
        sv.addView(col, new ScrollView.LayoutParams(-1, -1));
        setContentView(sv);
    }

    @Override protected void onResume() {
        super.onResume();
        open = this;
        AlarmService s = AlarmService.instance;
        if (s == null || !s.isRinging()) { finish(); return; }
        render();
    }

    @Override protected void onPause() {
        if (open == this) open = null;
        super.onPause();
    }

    private void render() {
        AlarmService s = AlarmService.instance;
        if (s == null || !s.isRinging()) { finish(); return; }
        List<AlarmService.Item> items = s.ringingItems();
        if (items.isEmpty()) return;
        title.setText(AlarmService.titleFor(items));
        lines.removeAllViews();
        int n = 0;
        for (AlarmService.Item it : items) {
            if (n++ >= 4) break;
            TextView t = Ui.text(this, it.l, 17, Ui.FG, false);
            t.setGravity(Gravity.START);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setColor(Ui.SURFACE);
            g.setStroke(Ui.dp(this, 1), Ui.LINE);
            g.setCornerRadius(Ui.dp(this, 14));
            t.setBackground(g);
            int p = Ui.dp(this, 16);
            t.setPadding(p, p, p, p);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = Ui.dp(this, 10);
            lines.addView(t, lp);
        }
        boolean rep = AlarmService.anyRepeating(items);
        ackBtn.setText(rep ? "J'ai vu (l'alarme revient dans 1 min 30)" : "J'ai vu");
    }

    private void openMain() {
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }

    @Override public void onBackPressed() {
        // Retour = « J'ai vu », pour ne jamais laisser l'alarme sans réponse.
        AlarmService.send(this, AlarmService.ACT_ACK);
        finish();
    }
}
