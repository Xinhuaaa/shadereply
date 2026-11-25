package io.shadereply;

import android.content.res.Resources;

final class NotificationResources {
    private NotificationResources() {}

    static int id(String name) {
        return Resources.getSystem().getIdentifier(name, "id", "android");
    }
}
