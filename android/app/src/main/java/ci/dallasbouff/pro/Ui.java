package ci.dallasbouff.pro;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.TextView;

/** Couleurs et composants communs, alignés sur l'identité Dallas Bouff'. */
final class Ui {
    private Ui() {}

    static final int BG = 0xFF0A0A0A;
    static final int SURFACE = 0xFF141414;
    static final int LINE = 0xFF2A2A2A;
    static final int FG = 0xFFF4F2EC;
    static final int DIM = 0xFF9A978F;
    static final int GOLD = 0xFFD4A537;
    static final int GOLD_INK = 0xFF0A0A0A;
    static final int ALERT = 0xFFE0705A;

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(bold ? "sans-serif-black" : "sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    static Button button(Context c, String label, boolean primary) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        b.setTextColor(primary ? GOLD_INK : FG);
        b.setMinHeight(dp(c, 60));
        b.setStateListAnimator(null);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(c, 16));
        if (primary) g.setColor(GOLD);
        else { g.setColor(SURFACE); g.setStroke(dp(c, 1), 0xFF3A3A3A); }
        b.setBackground(g);
        b.setPadding(dp(c, 18), dp(c, 14), dp(c, 18), dp(c, 14));
        return b;
    }
}
