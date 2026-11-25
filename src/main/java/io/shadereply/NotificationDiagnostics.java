package io.shadereply;

import android.app.Notification;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.regex.Pattern;

import io.github.libxposed.api.XposedModule;

final class NotificationDiagnostics {
    static final String REVISION = "io.shadereply.notification.REVISION";
    static final String MESSAGE_ID = "io.shadereply.notification.MESSAGE_ID";
    private static final String TRACE_TAG = "ShadeReplyTrace";
    private static final String ERROR_TAG = "ShadeReply";
    private static volatile boolean enabled;
    private static SharedPreferences.OnSharedPreferenceChangeListener listener;
    private static final Pattern RANDOM_ID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

    private NotificationDiagnostics() {}

    static void observe(XposedModule module) {
        if (listener != null) return;
        try {
            SharedPreferences prefs = module.getRemotePreferences(DiagnosticSettings.GROUP);
            SharedPreferences.OnSharedPreferenceChangeListener watch = (changed, key) -> {
                if (key == null || DiagnosticSettings.DETAILED.equals(key)) refresh(changed);
            };
            prefs.registerOnSharedPreferenceChangeListener(watch);
            listener = watch;
            refresh(prefs);
        } catch (RuntimeException error) {
            module.log(Log.WARN, ERROR_TAG, "Diagnostic settings unavailable: " + errorType(error), null);
        }
    }

    private static synchronized void refresh(SharedPreferences prefs) {
        enabled = prefs.getBoolean(DiagnosticSettings.DETAILED, false);
    }

    static String revision(Notification notification) {
        return notification.extras.getString(REVISION);
    }

    static void record(XposedModule module, String id, String stage, String detail) {
        if (enabled) writeTrace(module, Log.INFO, id, stage, detail);
    }

    static void warn(XposedModule module, String id, String stage, String detail) {
        if (enabled) {
            writeTrace(module, Log.WARN, id, stage, detail);
        } else {
            module.log(Log.WARN, ERROR_TAG, "stage=" + stage + " " + detail, null);
        }
    }

    private static void writeTrace(XposedModule module, int priority, String id, String stage, String detail) {
        String trace = id != null && RANDOM_ID.matcher(id).matches()
                ? id.substring(0, 8) + id.substring(9, 13) : "-";
        module.log(priority, TRACE_TAG, "trace=" + trace + " stage=" + stage + " " + detail, null);
    }

    static String errorType(Throwable failure) {
        Throwable cause = failure.getCause();
        return (cause == null ? failure : cause).getClass().getSimpleName();
    }
}
