package io.shadereply;

import android.app.Notification;
import android.graphics.Canvas;
import android.graphics.drawable.AnimatedImageDrawable;
import android.net.Uri;
import android.view.View;
import android.widget.ImageView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;

final class NotificationMedia {
    static final String PREVIEW = "io.shadereply.notification.MEDIA_PREVIEW";
    private static final int PREVIEW_HEIGHT_DP = 156;
    private static final float PREVIEW_SCALE = 0.70f;
    private static final int MIN_DISPLAY_HEIGHT_DP = 44;

    static void install(XposedModule module, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> image = loader.loadClass("com.android.internal.widget.MessagingImageMessage");
        Method bind = image.getDeclaredMethod("setMessage", Notification.MessagingStyle.Message.class, boolean.class);
        Method measure = image.getDeclaredMethod("onMeasure", int.class, int.class);
        Method draw = image.getDeclaredMethod("onDraw", Canvas.class);
        Method recycle = image.getDeclaredMethod("recycle");
        Method setSize = View.class.getDeclaredMethod("setMeasuredDimension", int.class, int.class);
        Field isolated = image.getDeclaredField("mIsIsolated");
        Field aspectRatio = image.getDeclaredField("mAspectRatio");
        Field drawable = image.getDeclaredField("mDrawable");
        bind.setAccessible(true);
        measure.setAccessible(true);
        draw.setAccessible(true);
        recycle.setAccessible(true);
        setSize.setAccessible(true);
        isolated.setAccessible(true);
        aspectRatio.setAccessible(true);
        drawable.setAccessible(true);
        Map<View, PreviewRender> previews = Collections.synchronizedMap(new WeakHashMap<>());
        Map<ImageView, AnimatedImageDrawable> installedAnimations = new WeakHashMap<>();

        module.hook(bind).intercept(chain -> {
            View view = (View) chain.getThisObject();
            previews.remove(view);
            if (!(chain.getArg(0) instanceof Notification.MessagingStyle.Message msg) || !isPreview(msg)) {
                return chain.proceed();
            }
            String trace = msg.getExtras().getString(NotificationDiagnostics.MESSAGE_ID);
            try {
                Object result = chain.proceed();
                boolean bound = Boolean.TRUE.equals(result);
                NotificationDiagnostics.record(module, trace, "systemui_media_bind", "success=" + bound);
                if (bound) previews.put(view, new PreviewRender(trace));
                return result;
            } catch (Throwable failure) {
                NotificationDiagnostics.warn(module, trace, "systemui_media_bind_failed",
                        "error=" + NotificationDiagnostics.errorType(failure));
                throw failure;
            }
        });
        module.hook(measure).intercept(chain -> {
            Object result = chain.proceed();
            View view = (View) chain.getThisObject();
            PreviewRender preview = previews.get(view);
            if (preview == null || preview.resizeFailed) return result;
            try {
                if (!isolated.getBoolean(view)) {
                    measurePreview(view, aspectRatio.getFloat(view), (int) chain.getArg(0),
                            (int) chain.getArg(1), setSize);
                }
            } catch (ReflectiveOperationException | RuntimeException failure) {
                preview.resizeFailed = true;
                NotificationDiagnostics.warn(module, preview.trace, "systemui_media_resize_failed",
                        "error=" + NotificationDiagnostics.errorType(failure));
            }
            return result;
        });
        module.hook(draw).intercept(chain -> {
            ImageView view = (ImageView) chain.getThisObject();
            PreviewRender preview = previews.get(view);
            boolean isolatedPreview = preview != null && view.isShown() && isolated.getBoolean(view);
            // The template draws its own drawable. Register animation with ImageView so it schedules redraws.
            AnimatedImageDrawable animation = preview != null && !preview.resizeFailed && view.isShown() && !isolatedPreview
                    && drawable.get(view) instanceof AnimatedImageDrawable imageDrawable ? imageDrawable : null;
            if (animation != null) {
                if (installedAnimations.get(view) != animation) {
                    clearAnimation(view, installedAnimations.remove(view));
                    if (view.getDrawable() == null) {
                        view.setImageDrawable(animation);
                        installedAnimations.put(view, animation);
                    }
                }
                if (!animation.isRunning()) animation.start();
            } else {
                clearAnimation(view, installedAnimations.remove(view));
            }
            Object result = chain.proceed();
            if (preview != null && !preview.drawn && view.isShown() && view.getWidth() > 0 && view.getHeight() > 0) {
                preview.drawn = true;
                NotificationDiagnostics.record(module, preview.trace, "systemui_media_draw",
                        "isolated=" + isolatedPreview + " width=" + view.getWidth() + " height=" + view.getHeight());
            }
            return result;
        });
        module.hook(recycle).intercept(chain -> {
            ImageView view = (ImageView) chain.getThisObject();
            previews.remove(view);
            clearAnimation(view, installedAnimations.remove(view));
            return chain.proceed();
        });
    }

    private static final class PreviewRender {
        final String trace;
        boolean drawn, resizeFailed;

        PreviewRender(String trace) {
            this.trace = trace;
        }
    }

    private static void clearAnimation(ImageView view, AnimatedImageDrawable animation) {
        if (animation != null && view.getDrawable() == animation) {
            animation.stop();
            view.setImageDrawable(null);
        }
    }

    private static boolean isPreview(Notification.MessagingStyle.Message message) {
        Uri uri = message.getDataUri();
        return message.getExtras().getBoolean(PREVIEW) && uri != null && "content".equals(uri.getScheme())
                && "com.tencent.mm.external.fileprovider".equals(uri.getAuthority())
                && uri.getPathSegments().contains("shadereply-previews");
    }

    private static void measurePreview(View view, float ratio, int widthSpec, int heightSpec, Method setSize)
            throws ReflectiveOperationException {
        if (ratio <= 0f || !Float.isFinite(ratio)) return;
        int widthMode = View.MeasureSpec.getMode(widthSpec);
        int heightMode = View.MeasureSpec.getMode(heightSpec);
        if (widthMode == View.MeasureSpec.EXACTLY || heightMode == View.MeasureSpec.EXACTLY) return;
        float density = view.getResources().getDisplayMetrics().density;
        int maxHeight = Math.round(PREVIEW_HEIGHT_DP * density);
        int widthLimit = widthMode == View.MeasureSpec.UNSPECIFIED ? Integer.MAX_VALUE : View.MeasureSpec.getSize(widthSpec);
        int heightLimit = heightMode == View.MeasureSpec.UNSPECIFIED ? maxHeight
                : Math.min(maxHeight, View.MeasureSpec.getSize(heightSpec));
        int width = Math.min(widthLimit, Math.round(heightLimit * ratio));
        int height = Math.min(heightLimit, Math.round(width / ratio));
        if (width < view.getMeasuredWidth() || height <= view.getMeasuredHeight()) {
            width = view.getMeasuredWidth();
            height = view.getMeasuredHeight();
        }
        if (width <= 0 || height <= 0) return;
        // The messaging layout may hide an image measured below its minimum display height.
        int minimum = Math.min(height, Math.round(MIN_DISPLAY_HEIGHT_DP * density));
        int scaledHeight = Math.max(minimum, Math.round(height * PREVIEW_SCALE));
        int scaledWidth = Math.max(1, Math.round(width * (scaledHeight / (float) height)));
        setSize.invoke(view, scaledWidth, scaledHeight);
    }
}
