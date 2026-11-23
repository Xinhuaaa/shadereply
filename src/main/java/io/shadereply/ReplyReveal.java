package io.shadereply;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.PathInterpolator;

import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.WeakHashMap;

final class ReplyReveal implements ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
    private static final long WAIT_FOR_LAYOUT_MS = 600;
    private static final WeakHashMap<View, WeakReference<ReplyReveal>> running = new WeakHashMap<>();

    private final View view;
    private final boolean editor;
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
        if (view == null || !view.isAttachedToWindow() || !ValueAnimator.areAnimatorsEnabled()) return;
        WeakReference<ReplyReveal> current = running.get(view);
        if (current != null && current.get() != null) return;
        ReplyReveal transition = new ReplyReveal(view, editor);
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

    private ReplyReveal(View view, boolean editor) {
        this.view = view;
        this.editor = editor;
        observer = view.getViewTreeObserver();
    }

    @Override
    public boolean onPreDraw() {
        if (finished || !view.isShown() || view.getWidth() == 0 || view.getHeight() == 0) return true;
        stopWaiting();
        originalClip = view.getClipBounds();
        originalAlpha = view.getTransitionAlpha();
        lastAlpha = originalAlpha;
        ownsClip = true;
        ownsAlpha = true;
        apply(0f);
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(editor ? 200 : 230);
        animator.setInterpolator(new PathInterpolator(0.24f, 0f, 0.24f, 1f));
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

    private void finish() {
        if (finished) return;
        finished = true;
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
