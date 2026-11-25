package io.shadereply;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;

import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.WeakHashMap;

final class ReplyReveal implements ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
    private static final long WAIT_FOR_LAYOUT_MS = 600;
    private static final WeakHashMap<View, WeakReference<ReplyReveal>> running = new WeakHashMap<>();

    private final View view;
    private final boolean editor;
    private final boolean pulled;
    private final View card;
    private final Rect replyButton;
    private final BannerCollapseContent.Layer morph;
    private final BannerReplyMotion replyMotion;
    private final int[] position = new int[2];
    private Runnable releaseOrigin;
    private final Runnable timeout = this::finish;
    private final ViewTreeObserver observer;
    private final Rect reveal = new Rect();
    private Rect originalClip;
    private float originalAlpha;
    private float lastAlpha;
    private boolean ownsClip;
    private boolean clipApplied;
    private boolean ownsAlpha;
    private boolean finished;
    private ValueAnimator animator;

    static void show(View view, boolean editor) {
        show(view, editor, false);
    }

    static void show(View view, boolean editor, boolean pulled) {
        show(view, editor, null, null, null, pulled);
    }

    static void showEditor(View editor, View card, Rect replyButton, Runnable releaseOrigin, boolean pulled) {
        show(editor, true, card, replyButton, releaseOrigin, pulled);
    }

    private static void show(View view, boolean editor, View card, Rect replyButton, Runnable releaseOrigin,
                             boolean pulled) {
        if (view == null || !view.isAttachedToWindow() || !ValueAnimator.areAnimatorsEnabled()) {
            if (releaseOrigin != null) releaseOrigin.run();
            return;
        }
        WeakReference<ReplyReveal> current = running.get(view);
        if (current != null && current.get() != null) {
            if (releaseOrigin != null) releaseOrigin.run();
            return;
        }
        ReplyReveal transition = new ReplyReveal(view, editor, card, replyButton, pulled);
        transition.releaseOrigin = releaseOrigin;
        running.put(view, new WeakReference<>(transition));
        view.addOnAttachStateChangeListener(transition);
        transition.observer.addOnPreDrawListener(transition);
        view.postDelayed(transition.timeout, WAIT_FOR_LAYOUT_MS);
        view.invalidate();
    }

    static void cancel(View view) {
        WeakReference<ReplyReveal> current = running.get(view);
        ReplyReveal transition = current == null ? null : current.get();
        if (transition != null) transition.finish();
    }

    private ReplyReveal(View view, boolean editor, View card, Rect replyButton, boolean pulled) {
        this.view = view;
        this.editor = editor;
        this.pulled = pulled;
        this.card = card;
        this.replyButton = replyButton;
        replyMotion = BannerReplyMotion.create(card, view, replyButton);
        morph = replyButton == null || replyMotion != null ? null : new BannerCollapseContent.Layer(view);
        observer = view.getViewTreeObserver();
    }

    @Override
    public boolean onPreDraw() {
        if (finished || !view.isShown() || view.getWidth() == 0 || view.getHeight() == 0) return true;
        stopWaiting();
        if (morph == null && replyMotion == null) {
            originalClip = view.getClipBounds();
            originalAlpha = view.getTransitionAlpha();
            lastAlpha = originalAlpha;
            ownsClip = true;
            ownsAlpha = true;
        }
        apply(0f);
        releaseOrigin();
        animator = ValueAnimator.ofFloat(0f, 1f);
        if (replyMotion != null) {
            animator.setDuration(pulled ? BannerReplyMotion.PULL_OPEN_DURATION_MS : BannerReplyMotion.OPEN_DURATION_MS);
        } else {
            long duration = morph != null ? 245 : editor ? 200 : 230;
            animator.setDuration(pulled ? duration * 4 / 5 : duration);
        }
        animator.setInterpolator(replyMotion != null ? new LinearInterpolator()
                : new PathInterpolator(0.24f, 0f, 0.24f, 1f));
        animator.addUpdateListener(animation -> apply((float) animation.getAnimatedValue()));
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                finish();
            }
        });
        animator.start();
        return true;
    }

    private void apply(float progress) {
        if (replyMotion != null) {
            replyMotion.render(progress, 1f);
            return;
        }
        if (morph != null) {
            morph.reset();
            morph.morph(card, replyButton, 1f - progress, 0.48f + 0.52f * progress, position);
            return;
        }
        if (clipApplied && ownsClip && !Objects.equals(reveal, view.getClipBounds())) ownsClip = false;
        if (ownsClip) {
            int width = view.getWidth(), height = view.getHeight();
            int left = 0, top = 0, right = width, bottom = height;
            if (originalClip != null) {
                left = originalClip.left;
                top = originalClip.top;
                right = originalClip.right;
                bottom = originalClip.bottom;
            }
            float start = editor ? 0.72f : 0.82f;
            int edge = top + Math.round((bottom - top) * (start + (1f - start) * progress));
            reveal.set(left, top, right, edge);
            view.setClipBounds(reveal);
            clipApplied = true;
        }
        if (ownsAlpha && Float.compare(view.getTransitionAlpha(), lastAlpha) == 0) {
            float start = editor ? 0.60f : 0.75f;
            lastAlpha = originalAlpha * (start + (1f - start) * progress);
            view.setTransitionAlpha(lastAlpha);
        } else {
            ownsAlpha = false;
        }
    }

    private void stopWaiting() {
        if (observer.isAlive()) observer.removeOnPreDrawListener(this);
        view.removeCallbacks(timeout);
    }

    private void releaseOrigin() {
        if (releaseOrigin == null) return;
        card.removeCallbacks(releaseOrigin);
        releaseOrigin.run();
        releaseOrigin = null;
    }

    private void finish() {
        if (finished) return;
        finished = true;
        releaseOrigin();
        if (replyMotion != null) replyMotion.finish();
        if (morph != null) morph.reset();
        stopWaiting();
        view.removeOnAttachStateChangeListener(this);
        if (animator != null) {
            animator.removeAllUpdateListeners();
            animator.removeAllListeners();
            animator.cancel();
        }
        if (ownsClip && Objects.equals(reveal, view.getClipBounds())) view.setClipBounds(originalClip);
        if (ownsAlpha && Float.compare(view.getTransitionAlpha(), lastAlpha) == 0)
            view.setTransitionAlpha(originalAlpha);
        running.remove(view);
    }

    @Override
    public void onViewAttachedToWindow(View view) {}

    @Override
    public void onViewDetachedFromWindow(View view) {
        finish();
    }
}
