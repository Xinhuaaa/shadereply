package io.shadereply;

import android.content.Context;
import android.content.SharedPreferences;

final class DiagnosticSettings {
    static final String GROUP = "diagnostics";
    static final String DETAILED = "detailed_logging";

    private DiagnosticSettings() {}

    private static SharedPreferences local(Context context) {
        return context.createDeviceProtectedStorageContext().getSharedPreferences(GROUP, Context.MODE_PRIVATE);
    }

    static boolean readLocal(Context context) {
        return local(context).getBoolean(DETAILED, false);
    }

    static boolean saveLocal(Context context, boolean enabled) {
        return local(context).edit().putBoolean(DETAILED, enabled).commit();
    }
}
