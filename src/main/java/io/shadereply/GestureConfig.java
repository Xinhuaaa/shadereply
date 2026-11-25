package io.shadereply;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;

record GestureConfig(boolean hold, int holdMs, boolean pull, int bannerDp, int shadeDp) {
    static final String AUTHORITY = "io.shadereply.gestures";
    static final Uri URI = Uri.parse("content://" + AUTHORITY + "/gestures");
    static final String[] COLUMNS = {"hold", "hold_ms", "pull", "banner_dp", "shade_dp"};
    static final int HOLD_MIN = 350, HOLD_MAX = 1500;
    static final int BANNER_MIN = 16, BANNER_MAX = 96;
    static final int SHADE_MIN = 20, SHADE_MAX = 120;
    static final GestureConfig DEFAULT = new GestureConfig(true, 650, true, 45, 87);

    GestureConfig {
        holdMs = Math.max(HOLD_MIN, Math.min(HOLD_MAX, holdMs));
        bannerDp = Math.max(BANNER_MIN, Math.min(BANNER_MAX, bannerDp));
        shadeDp = Math.max(SHADE_MIN, Math.min(SHADE_MAX, shadeDp));
    }

    private static SharedPreferences prefs(Context context) {
        return context.createDeviceProtectedStorageContext()
                .getSharedPreferences("gestures", Context.MODE_PRIVATE);
    }

    static GestureConfig readLocal(Context context) {
        var prefs = prefs(context);
        return new GestureConfig(prefs.getBoolean(COLUMNS[0], DEFAULT.hold),
                prefs.getInt(COLUMNS[1], DEFAULT.holdMs), prefs.getBoolean(COLUMNS[2], DEFAULT.pull),
                prefs.getInt(COLUMNS[3], DEFAULT.bannerDp), prefs.getInt(COLUMNS[4], DEFAULT.shadeDp));
    }

    boolean save(Context context) {
        boolean saved = prefs(context).edit().putBoolean(COLUMNS[0], hold)
                .putInt(COLUMNS[1], holdMs).putBoolean(COLUMNS[2], pull)
                .putInt(COLUMNS[3], bannerDp).putInt(COLUMNS[4], shadeDp).commit();
        if (saved) context.getContentResolver().notifyChange(URI, null, 0);
        return saved;
    }

    Object[] toRow() {
        return new Object[] {hold ? 1 : 0, holdMs, pull ? 1 : 0, bannerDp, shadeDp};
    }

    static GestureConfig readFromProvider(Context context) {
        try (Cursor cursor = context.getContentResolver().query(URI, COLUMNS, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            return new GestureConfig(cursor.getInt(0) != 0, cursor.getInt(1),
                    cursor.getInt(2) != 0, cursor.getInt(3), cursor.getInt(4));
        }
    }
}
