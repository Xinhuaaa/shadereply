package io.shadereply;

import android.graphics.Rect;
import android.view.View;

final class BannerCollapseContent {
    private final View card;
    private final Layer editor, history, actions;
    private final Rect replyButton;
    private final BannerReplyMotion replyMotion;
    private final float density;
    private final int[] position = new int[2];

    BannerCollapseContent(View card, View editor, View history, View actions, Rect replyButton) {
        this.card = card;
        this.editor = layer(card, editor);
        this.history = layer(card, history);
        this.actions = layer(card, actions);
        this.replyButton = replyButton;
        replyMotion = BannerReplyMotion.create(card, editor, replyButton);
        density = card.getResources().getDisplayMetrics().density;
    }

    void render(float progress, int height) {
        card.getLocationInWindow(position);
        int bottom = position[1] + height;
        if (history != null) {
            float fade = phase(progress, 0.12f, 0.78f);
            history.apply(1f - fade, -9f * density * fade);
        }
        if (replyMotion != null) {
            replyMotion.render(1f - progress, 1f - phase(progress, 0.93f, 1f));
        } else if (editor != null) {
            if (replyButton != null) {
                editor.morph(card, replyButton, phase(progress, 0f, 0.98f),
                        1f - phase(progress, 0.63f, 1f), position);
            } else {
                float fade = phase(progress, 0.30f, 0.88f);
                editor.apply(1f - fade, -12f * density * fade);
            }
        }
        if (actions != null) {
            float reveal = phase(progress, 0.56f, 0.98f);
            actions.apply(reveal, 8f * density * (1f - reveal) - overflow(actions.view, bottom));
        }
    }

    void reset() {
        if (replyMotion != null) replyMotion.resetTransforms();
        if (history != null) history.reset();
        if (editor != null) editor.reset();
        if (actions != null) actions.reset();
    }

    void finish() {
        reset();
        if (replyMotion != null) replyMotion.finish();
    }

    private int overflow(View view, int bottom) {
        view.getLocationInWindow(position);
        return Math.max(0, position[1] + view.getHeight() - bottom);
    }

    private static float phase(float progress, float start, float end) {
        float part = Math.max(0f, Math.min(1f, (progress - start) / (end - start)));
        return part * part * (3f - 2f * part);
    }

    private static Layer layer(View card, View view) {
        if (view == null || view == card) return null;
        ReplyReveal.cancel(view);
        return new Layer(view);
    }

    static final class Layer {
        final View view;
        private float originalAlpha, originalX, originalY, originalScaleX, originalScaleY;
        private float appliedAlpha, appliedX, appliedY, appliedScaleX, appliedScaleY;
        private boolean applied;

        Layer(View view) {
            this.view = view;
        }

        void apply(float opacity, float offset) {
            apply(opacity, 0f, offset, 1f, 1f);
        }

        void morph(View card, Rect button, float fraction, float opacity, int[] position) {
            if (view.getWidth() <= 0 || view.getHeight() <= 0) return;
            card.getLocationInWindow(position);
            float targetX = position[0] + button.exactCenterX();
            float targetY = position[1] + button.exactCenterY();
            view.getLocationInWindow(position);
            float width = view.getWidth() * view.getScaleX();
            float height = view.getHeight() * view.getScaleY();
            if (width <= 0f || height <= 0f) return;
            float scaleX = 1f + (Math.min(1f, button.width() / width) - 1f) * fraction;
            float scaleY = 1f + (Math.min(1f, button.height() / height) - 1f) * fraction;
            float offsetX = (targetX - position[0] - width * 0.5f) * fraction;
            float offsetY = (targetY - position[1] - height * 0.5f) * fraction;
            apply(opacity, offsetX, offsetY, scaleX, scaleY);
        }

        void apply(float opacity, float offsetX, float offsetY, float scaleX, float scaleY) {
            originalAlpha = view.getTransitionAlpha();
            originalX = view.getTranslationX();
            originalY = view.getTranslationY();
            originalScaleX = view.getScaleX();
            originalScaleY = view.getScaleY();
            appliedAlpha = originalAlpha * opacity;
            appliedX = originalX + offsetX;
            appliedY = originalY + offsetY;
            appliedScaleX = originalScaleX * scaleX;
            appliedScaleY = originalScaleY * scaleY;
            view.setTransitionAlpha(appliedAlpha);
            view.setTranslationX(appliedX);
            view.setTranslationY(appliedY);
            view.setScaleX(appliedScaleX);
            view.setScaleY(appliedScaleY);
            applied = true;
        }

        void reset() {
            if (!applied) return;
            if (view.getTransitionAlpha() == appliedAlpha) view.setTransitionAlpha(originalAlpha);
            if (view.getTranslationX() == appliedX) view.setTranslationX(originalX);
            if (view.getTranslationY() == appliedY) view.setTranslationY(originalY);
            if (view.getScaleX() == appliedScaleX) view.setScaleX(originalScaleX);
            if (view.getScaleY() == appliedScaleY) view.setScaleY(originalScaleY);
            applied = false;
        }
    }
}
