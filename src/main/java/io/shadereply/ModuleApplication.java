package io.shadereply;

import android.app.Application;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

public final class ModuleApplication extends Application implements XposedServiceHelper.OnServiceListener {
    static final String SYSTEM_UI = "com.android.systemui";
    private volatile XposedService service;
    private Runnable connectionChanged;

    @Override public void onCreate() {
        super.onCreate();
        XposedServiceHelper.registerListener(this);
        AutoUpdateCheckService.ensureScheduled(this);
    }

    XposedService service() { return service; }

    void watchConnection(Runnable listener) {
        connectionChanged = listener;
    }

    @Override public void onServiceBind(XposedService connected) {
        getMainExecutor().execute(() -> {
            service = connected;
            syncWeChatSettings();
            syncDiagnosticSettings();
            if (connectionChanged != null) connectionChanged.run();
        });
    }

    @Override public void onServiceDied(XposedService disconnected) {
        getMainExecutor().execute(() -> {
            if (service != disconnected) return;
            service = null;
            if (connectionChanged != null) connectionChanged.run();
        });
    }

    boolean syncDiagnosticSettings() {
        XposedService connected = service;
        if (connected == null) return false;
        try {
            return connected.getRemotePreferences(DiagnosticSettings.GROUP).edit()
                    .putBoolean(DiagnosticSettings.DETAILED, DiagnosticSettings.readLocal(this)).commit();
        } catch (RuntimeException error) {
            android.util.Log.w("ShadeReply", "Could not sync diagnostic settings", error);
            return false;
        }
    }

    boolean syncWeChatSettings() {
        XposedService connected = service;
        if (connected == null) return false;
        try {
            var prefs = WeChatConfig.Companion.local(this);
            return WeChatConfig.Companion.read(prefs).save(
                    connected.getRemotePreferences(WeChatConfig.GROUP));
        } catch (RuntimeException error) {
            android.util.Log.w("ShadeReplyWeChat", "Could not sync WeChat settings", error);
            return false;
        }
    }
}
