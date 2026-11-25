package io.shadereply;

import android.animation.ArgbEvaluator;
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
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.TextView;

final class BannerReplyMotion {
    static final long OPEN_DURATION_MS = 475;
    private static final PathInterpolator FIELD_EASING = new PathInterpolator(0.34f, 0f, 0.27f, 1f);
    private static final PathInterpolator SEND_EASING = new PathInterpolator(0.30f, 0.16f, 0.38f, 1.11f);
    private static final int DEFAULT_FIELD_COLOR = 0x29ffffff;
    private static final ArgbEvaluator COLORS = new ArgbEvaluator();

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
        int tint = fillColor(field.view, DEFAULT_FIELD_COLOR);
        pill.paint.setColor(tint);
        pill.setAlpha(Math.round(Color.alpha(tint) * opacity));
        if (!pillAttached && opacity > 0f) {
            pill.setBounds(0, 0, card.getWidth(), card.getHeight());
            card.getOverlay().add(pill);
            pillAttached = true;
        }
        if (pillAttached) pill.invalidateSelf();
    }

    private static int fillColor(View view, int fallback) {
        var tint = view.getBackgroundTintList();
        if (tint != null) return tint.getColorForState(view.getDrawableState(), tint.getDefaultColor());
        Integer color = drawableColor(view.getBackground(), view.getDrawableState());
        return color == null ? fallback : color;
    }

    private static Integer drawableColor(Drawable background, int[] state) {
        if (background instanceof GradientDrawable gradient && gradient.getColor() != null)
            return gradient.getColor().getColorForState(state, gradient.getColor().getDefaultColor());
        if (background instanceof ColorDrawable color) return color.getColor();
        if (background instanceof InsetDrawable inset) return drawableColor(inset.getDrawable(), state);
        if (background instanceof LayerDrawable layers) {
            for (int i = layers.getNumberOfLayers() - 1; i >= 0; i--) {
                if (layers.getId(i) == android.R.id.mask) continue;
                Integer color = drawableColor(layers.getDrawable(i), state);
                if (color != null) return color;
            }
        }
        return null;
    }

    static PullOrigin pullOrigin(View card, View button, View editor, View expandedActions, Rect origin) {
        if (!(button instanceof TextView label) || label.getLayout() == null
                || origin == null || origin.isEmpty() || editor == null) return null;
        View field = child(editor, "remote_input_content");
        return field == null ? null : new PullOrigin(card, label, field, expandedActions, origin);
    }

    static final class PullOrigin extends Pill implements View.OnAttachStateChangeListener {
        private final View card, field;
        private final Rect origin, current;
        private final int[] position = new int[2];
        private final BannerCollapseContent.Layer expandedActions;
        private final Layout label;
        private final int labelX, labelY, buttonColor;
        private final Runnable timeout = this::finish;
        private int labelAlpha;
        private boolean attached;

        PullOrigin(View card, TextView button, View field, View expandedActions, Rect origin) {
            this.card = card;
            this.field = field;
            this.expandedActions = expandedActions == null ? null : new BannerCollapseContent.Layer(expandedActions);
            this.origin = new Rect(origin);
            current = new Rect(origin);
            label = button.getLayout();
            labelX = button.getCompoundPaddingLeft() - button.getScrollX();
            labelY = button.getBaseline() - label.getLineBaseline(0) - button.getScrollY();
            int text = button.getCurrentTextColor();
            buttonColor = fillColor(button, (text & 0x00ffffff) | 0x2d000000);
        }

        void follow(float progress, int heightChange, float openingProgress) {
            if (!card.isAttachedToWindow()) return;
            current.set(origin);
            current.offset(0, heightChange);
            if (openingProgress > 0f) beginWidening(0.22f * openingProgress);
            rect.set(current);
            if (expandedActions != null) {
                expandedActions.reset();
                expandedActions.apply(0f, 0f);
            }
            int tint = fillColor(field, DEFAULT_FIELD_COLOR);
            float blend = smooth(phase(progress, 0f, 0.90f));
            paint.setColor((int) COLORS.evaluate(blend, buttonColor, tint));
            labelAlpha = Math.round(255f * (1f - smooth(phase(progress, 0.04f, 0.76f))));
            setBounds(0, 0, card.getWidth(), Math.max(card.getHeight(), current.bottom));
            if (!attached) {
                card.getOverlay().add(this);
                card.addOnAttachStateChangeListener(this);
                attached = true;
            }
            invalidateSelf();
        }

        private void beginWidening(float fraction) {
            int inset = Math.round(16f * card.getResources().getDisplayMetrics().density);
            int left = inset, right = card.getWidth() - inset;
            if (field.getWidth() > origin.width()) {
                card.getLocationInWindow(position);
                int cardX = position[0];
                field.getLocationInWindow(position);
                int fieldLeft = position[0] - cardX;
                if (fieldLeft >= 0 && fieldLeft + field.getWidth() <= card.getWidth()) {
                    left = fieldLeft;
                    right = fieldLeft + field.getWidth();
                }
            }
            current.left = Math.round(lerp(origin.left, left, fraction));
            current.right = Math.round(lerp(origin.right, right, fraction));
        }

        Rect currentBounds() {
            return attached ? new Rect(current) : null;
        }

        void waitForEditor() {
            if (attached) card.postDelayed(timeout, 950);
        }

        void finish() {
            if (!attached) return;
            attached = false;
            card.removeCallbacks(timeout);
            card.removeOnAttachStateChangeListener(this);
            card.getOverlay().remove(this);
            if (expandedActions != null) expandedActions.reset();
        }

        @Override public void draw(Canvas canvas) {
            super.draw(canvas);
            if (labelAlpha == 0) return;
            int saved = canvas.saveLayerAlpha(rect.left, rect.top, rect.right, rect.bottom, labelAlpha);
            try {
                canvas.translate(rect.left + labelX, rect.top + labelY);
                label.draw(canvas);
            } finally {
                canvas.restoreToCount(saved);
            }
        }

        @Override public void onViewAttachedToWindow(View view) {}
        @Override public void onViewDetachedFromWindow(View view) { finish(); }
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

    private static class Pill extends Drawable {
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
