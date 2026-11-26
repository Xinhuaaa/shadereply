package io.shadereply;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.view.animation.PathInterpolator;

import java.util.HashMap;
import java.util.function.BooleanSupplier;

final class ReplyBackdrop implements View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
    private static final int BLUR_FLAG = LayoutParams.FLAG_BLUR_BEHIND;
    private static final HashMap<View, ReplyBackdrop> ACTIVE_WINDOWS = new HashMap<>();
    private static final PathInterpolator EASE_OUT = new PathInterpolator(0.20f, 0.68f, 0.24f, 1f);

    private final View window;
    private final WindowManager manager;
    private final int radius;
    private final int originalRadius;
    private final boolean originalFlag;
    private View watchedView;
    private BooleanSupplier editorOpen;
    private ValueAnimator animation;
    private ValueAnimator swipeRestore;
    private float animationTarget;
    private float progress;
    private float swipeVisibility = 1f;
    private int appliedRadius;
    private boolean closed;

    static ReplyBackdrop forView(View view) {
        View root = view.getRootView();
        ReplyBackdrop active = ACTIVE_WINDOWS.get(root);
        if (active != null) return active;
        if (!root.isAttachedToWindow() || !(root.getLayoutParams() instanceof LayoutParams attrs)) return null;
        WindowManager manager = root.getContext().getSystemService(WindowManager.class);
        if (manager == null || !manager.isCrossWindowBlurEnabled()) return null;
        if ((attrs.flags & BLUR_FLAG) != 0 && attrs.getBlurBehindRadius() > 0) return null;
        ReplyBackdrop backdrop = new ReplyBackdrop(root, manager, attrs);
        ACTIVE_WINDOWS.put(root, backdrop);
        root.addOnAttachStateChangeListener(backdrop);
        return backdrop;
    }

    static ReplyBackdrop activeForView(View view) {
        return ACTIVE_WINDOWS.get(view.getRootView());
    }

    private ReplyBackdrop(View window, WindowManager manager, LayoutParams attrs) {
        this.window = window;
        this.manager = manager;
        originalRadius = appliedRadius = attrs.getBlurBehindRadius();
        originalFlag = (attrs.flags & BLUR_FLAG) != 0;
        radius = Math.min(48, Math.round(12 * window.getResources().getDisplayMetrics().density));
    }

    float startDrag(View row) {
        float visibleProgress = progress * swipeVisibility;
        stopWatchingView();
        stopAnimation();
        stopSwipeRestore();
        swipeVisibility = 1f;
        setProgress(visibleProgress);
        watchedView = row;
        if (row != window) row.addOnAttachStateChangeListener(this);
        return progress;
    }

    void drag(float fraction) {
        setProgress(Math.max(0f, Math.min(1f, fraction)));
    }

    float progress() {
        return progress;
    }

    View editorView() {
        return editorOpen == null ? null : watchedView;
    }

    void setSwipeVisibility(float visibility) {
        stopSwipeRestore();
        swipeVisibility = visibility;
        setProgress(progress);
    }

    void restoreAfterSwipe() {
        if (closed || editorOpen == null || swipeVisibility == 1f) return;
        if (!ValueAnimator.areAnimatorsEnabled()) {
            setSwipeVisibility(1f);
            return;
        }
        stopSwipeRestore();
        swipeRestore = ValueAnimator.ofFloat(swipeVisibility, 1f);
        swipeRestore.setDuration(160);
        swipeRestore.setInterpolator(EASE_OUT);
        swipeRestore.addUpdateListener(frame -> {
            if (editorOpen == null || !editorOpen.getAsBoolean()) {
                collapse();
                return;
            }
            swipeVisibility = (float) frame.getAnimatedValue();
            setProgress(progress);
        });
        swipeRestore.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator ended) { swipeRestore = null; }
        });
        swipeRestore.start();
    }

    void expand() {
        animateTo(1f, 150);
    }

    void keepForEditor(View view, BooleanSupplier isOpen) {
        if (closed) return;
        stopWatchingView();
        watchedView = view;
        editorOpen = isOpen;
        if (view != window) view.addOnAttachStateChangeListener(this);
        window.getViewTreeObserver().addOnPreDrawListener(this);
        expand();
    }

    void collapse() {
        stopSwipeRestore();
        stopWatchingView();
        animateTo(0f, 200);
    }

    @Override public boolean onPreDraw() {
        if (editorOpen != null) {
            if (!editorOpen.getAsBoolean()) collapse();
            else setProgress(progress);
        }
        return true;
    }

    private void animateTo(float target, int duration) {
        if (closed || animation != null && animationTarget == target) return;
        stopAnimation();
        if (progress == target || !ValueAnimator.areAnimatorsEnabled()) {
            setProgress(target);
            if (target == 0f) close();
            return;
        }
        animationTarget = target;
        animation = ValueAnimator.ofFloat(progress, target);
        animation.setDuration(duration);
        animation.setInterpolator(EASE_OUT);
        animation.addUpdateListener(frame -> setProgress((float) frame.getAnimatedValue()));
        animation.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator ended) {
                animation = null;
                if (target == 0f) close();
            }
        });
        animation.start();
    }

    private void setProgress(float value) {
        if (closed) return;
        progress = value;
        if (!(window.getLayoutParams() instanceof LayoutParams attrs)) return;
        int nextRadius = Math.round(radius * value * swipeVisibility);
        boolean blur = nextRadius > 0 || originalFlag;
        if (nextRadius == 0) nextRadius = originalRadius;
        boolean hasBlur = (attrs.flags & BLUR_FLAG) != 0;
        int currentRadius = attrs.getBlurBehindRadius();
        if (hasBlur && currentRadius > 0 && currentRadius != appliedRadius) return;
        if (currentRadius == nextRadius && blur == hasBlur) return;
        int previousFlags = attrs.flags;
        attrs.flags = blur ? attrs.flags | BLUR_FLAG : attrs.flags & ~BLUR_FLAG;
        attrs.setBlurBehindRadius(nextRadius);
        try {
            if (window.isAttachedToWindow()) manager.updateViewLayout(window, attrs);
            appliedRadius = nextRadius;
        } catch (RuntimeException error) {
            attrs.flags = previousFlags;
            attrs.setBlurBehindRadius(currentRadius);
            Log.w("WeChatShadeReply", "Could not update the reply window blur", error);
        }
    }

    private void stopAnimation() {
        if (animation == null) return;
        animation.removeAllListeners();
        animation.removeAllUpdateListeners();
        animation.cancel();
        animation = null;
    }

    private void stopSwipeRestore() {
        if (swipeRestore == null) return;
        swipeRestore.removeAllListeners();
        swipeRestore.removeAllUpdateListeners();
        swipeRestore.cancel();
        swipeRestore = null;
    }

    private void stopWatchingView() {
        if (watchedView == null) return;
        if (watchedView != window) watchedView.removeOnAttachStateChangeListener(this);
        if (editorOpen != null) {
            ViewTreeObserver observer = window.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnPreDrawListener(this);
        }
        watchedView = null;
        editorOpen = null;
    }

    private void close() {
        if (closed) return;
        stopAnimation();
        stopSwipeRestore();
        stopWatchingView();
        setProgress(0f);
        closed = true;
        window.removeOnAttachStateChangeListener(this);
        ACTIVE_WINDOWS.remove(window);
    }

    @Override public void onViewAttachedToWindow(View view) {}

    @Override public void onViewDetachedFromWindow(View view) {
        if (view == window) close();
        else collapse();
    }
}
