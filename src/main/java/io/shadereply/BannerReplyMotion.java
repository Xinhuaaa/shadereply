package io.shadereply;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;

final class BannerReplyMotion {
    static final long OPEN_DURATION_MS = 475;
    private static final PathInterpolator FIELD_EASING = new PathInterpolator(0.34f, 0f, 0.27f, 1f);
    private static final PathInterpolator SEND_EASING = new PathInterpolator(0.30f, 0.16f, 0.38f, 1.11f);
    private static final int DEFAULT_FIELD_COLOR = 0x29ffffff;

    private final View card;
    private final Rect origin;
    private final BannerCollapseContent.Layer field, text, send;
    private final ViewGroup sendParent;
    private final boolean clipChildren, clipPadding;
    private final int[] position = new int[2];
    private final Pill pill = new Pill();
    private final float density;
    private boolean clippingChanged, pillAttached;

    static Runnable holdOrigin(View card, Rect origin) {
        if (origin == null || origin.isEmpty() || !card.isAttachedToWindow()
                || !android.animation.ValueAnimator.areAnimatorsEnabled()) return null;
        Pill placeholder = new Pill();
        placeholder.rect.set(origin);
        placeholder.paint.setColor(DEFAULT_FIELD_COLOR);
        placeholder.setBounds(0, 0, card.getWidth(), card.getHeight());
        card.getOverlay().add(placeholder);
        Runnable release = () -> card.getOverlay().remove(placeholder);
        card.postDelayed(release, 650);
        return release;
    }

    static BannerReplyMotion create(View card, View editor, Rect origin) {
        if (editor == null || origin == null || origin.isEmpty()) return null;
        View field = child(editor, "remote_input_content");
        if (field == null) return null;
        return new BannerReplyMotion(card, origin, field,
                child(field, "remote_input_text"), child(editor, "remote_input_send"));
    }

    private BannerReplyMotion(View card, Rect origin, View field, View text, View send) {
        this.card = card;
        this.origin = origin;
        this.field = new BannerCollapseContent.Layer(field);
        this.text = text == null ? null : new BannerCollapseContent.Layer(text);
        this.send = send == null ? null : new BannerCollapseContent.Layer(send);
        density = field.getResources().getDisplayMetrics().density;
        sendParent = send != null && send.getParent() instanceof ViewGroup group ? group : null;
        clipChildren = sendParent != null && sendParent.getClipChildren();
        clipPadding = sendParent != null && sendParent.getClipToPadding();
    }

    void render(float progress, float opacity) {
        resetTransforms();
        float fieldProgress = FIELD_EASING.getInterpolation(phase(progress, 0f, 0.65f));
        float handoff = smooth(phase(progress, 0.63f, 0.80f));
        float departure = phase(progress, 0.55f, 1f);
        float sendShift = send == null ? 0f : sendTravel();
        if (field.view.getWidth() > 0 && field.view.getHeight() > 0) {
            drawPill(fieldProgress, (1f - handoff) * opacity);
            field.apply(handoff * opacity, 0f);
        }
        if (text != null) text.apply(smooth(phase(progress, 0.62f, 0.83f)), 0f);
        if (send != null) {
            allowSendTravel();
            float spring = SEND_EASING.getInterpolation(departure);
            float sendOpacity = smooth(phase(departure, 0.02f, 0.49f));
            float scale = 0.54f + 0.46f * spring;
            send.apply(sendOpacity, sendShift * (1f - spring), 0f, scale, scale);
        }
    }

    void resetTransforms() {
        field.reset();
        if (text != null) text.reset();
        if (send != null) send.reset();
    }

    void finish() {
        resetTransforms();
        if (pillAttached) {
            card.getOverlay().remove(pill);
            pillAttached = false;
        }
        if (clippingChanged) {
            sendParent.setClipChildren(clipChildren);
            sendParent.setClipToPadding(clipPadding);
            clippingChanged = false;
        }
    }

    private float sendTravel() {
        field.view.getLocationInWindow(position);
        int fieldLeft = position[0], fieldRight = fieldLeft + field.view.getWidth();
        send.view.getLocationInWindow(position);
        float halfButton = send.view.getWidth() * 0.5f;
        float sendCenter = position[0] + halfButton;
        float inset = halfButton + 15f * density;
        float from = sendCenter >= (fieldLeft + fieldRight) * 0.5f
                ? Math.max(fieldLeft + halfButton, fieldRight - inset)
                : Math.min(fieldRight - halfButton, fieldLeft + inset);
        return from - sendCenter;
    }

    private void drawPill(float progress, float opacity) {
        card.getLocationInWindow(position);
        int cardX = position[0], cardY = position[1];
        field.view.getLocationInWindow(position);
        int left = position[0] - cardX, top = position[1] - cardY;
        pill.rect.set(
                lerp(origin.left, left, progress),
                lerp(origin.top, top, progress),
                lerp(origin.right, left + field.view.getWidth(), progress),
                lerp(origin.bottom, top + field.view.getHeight(), progress));
        int tint = fieldColor(field.view.getBackground());
        pill.paint.setColor(tint);
        pill.setAlpha(Math.round(Color.alpha(tint) * opacity));
        if (!pillAttached && opacity > 0f) {
            pill.setBounds(0, 0, card.getWidth(), card.getHeight());
            card.getOverlay().add(pill);
            pillAttached = true;
        }
        if (pillAttached) pill.invalidateSelf();
    }

    private static int fieldColor(Drawable background) {
        if (background instanceof GradientDrawable gradient && gradient.getColor() != null)
            return gradient.getColor().getDefaultColor();
        if (background instanceof ColorDrawable color) return color.getColor();
        return DEFAULT_FIELD_COLOR;
    }

    private static float lerp(float from, float to, float progress) {
        return from + (to - from) * progress;
    }

    private void allowSendTravel() {
        if (sendParent == null || clippingChanged) return;
        sendParent.setClipChildren(false);
        sendParent.setClipToPadding(false);
        clippingChanged = true;
    }

    private static View child(View root, String name) {
        int id = root.getResources().getIdentifier(name, "id", "com.android.systemui");
        return id == 0 ? null : root.findViewById(id);
    }

    private static float phase(float progress, float start, float end) {
        return Math.max(0f, Math.min(1f, (progress - start) / (end - start)));
    }

    private static float smooth(float progress) {
        return progress * progress * (3f - 2f * progress);
    }

    private static final class Pill extends Drawable {
        final RectF rect = new RectF();
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        @Override public void draw(Canvas canvas) {
            float radius = rect.height() * 0.5f;
            canvas.drawRoundRect(rect, radius, radius, paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }

        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }

        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
