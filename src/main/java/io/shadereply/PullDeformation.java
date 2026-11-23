package io.shadereply;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Matrix;
import android.view.Choreographer;
import android.view.View;
import android.view.animation.PathInterpolator;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

final class PullDeformation implements View.OnAttachStateChangeListener {
    private static final long SETTLE_MS = 285;
    private static final long IDLE_RESET_MS = 1400;
    private static final float MAX_STRETCH = 0.10f;
    private static final float MAX_NARROWING = 0.032f;
    private static final float MAX_TRAVEL_DP = 16f;
    private static final WeakHashMap<View, WeakReference<PullDeformation>> active = new WeakHashMap<>();

    private final View row;
    private final View card;
    private final float density;
    private final Matrix matrix = new Matrix();
    private final Runnable idleReset = this::release;
    private final Choreographer choreographer = Choreographer.getInstance();
    private final Choreographer.FrameCallback followFrame = this::onFrame;
    private ValueAnimator settle;
    private float amount;
    private float targetAmount;
    private long lastFrameNanos;
    private boolean frameQueued;
    private boolean ownsMatrix;
    private boolean closed;

    PullDeformation(View row, View card) {
        this.row = row;
        this.card = card;
        density = row.getResources().getDisplayMetrics().density;
        row.addOnAttachStateChangeListener(this);
    }

    private boolean canAnimate() {
        if (closed || settle != null) return false;
        if (!row.isAttachedToWindow() || !ValueAnimator.areAnimatorsEnabled()) {
            close();
            return false;
        }
        WeakReference<PullDeformation> reference = active.get(row);
        PullDeformation previous = reference == null ? null : reference.get();
        if (previous != this) {
            // A second pull can take over while the first is still rebounding.
            if (previous != null) {
                if (previous.ownsMatrix && previous.matrix.equals(row.getAnimationMatrix())) {
                    amount = previous.amount;
                    matrix.set(previous.matrix);
                    ownsMatrix = true;
                    previous.ownsMatrix = false;
                }
                previous.close();
            }
            active.put(row, new WeakReference<>(this));
        }
        return true;
    }

    void pulse() {
        if (!canAnimate()) return;
        animateToRest(ValueAnimator.ofFloat(amount, Math.max(amount, 0.27f), 0f), 260);
    }

    void follow(float downwardPixels) {
        followDistance(Math.max(0f, downwardPixels / density - 3f));
    }

    void followBannerDrag(float downwardPixels) {
        float distance = Math.max(0f, downwardPixels / density);
        followDistance(distance * distance / (distance + 5f));
    }

    private void followDistance(float distance) {
        if (!canAnimate()) return;
        targetAmount = distance / (distance + 18f);
        if (targetAmount == 0f && !ownsMatrix) return;
        queueFrame();
        row.removeCallbacks(idleReset);
        row.postDelayed(idleReset, IDLE_RESET_MS);
    }

    private void queueFrame() {
        if (frameQueued) return;
        frameQueued = true;
        choreographer.postFrameCallback(followFrame);
    }

    private void onFrame(long timeNanos) {
        frameQueued = false;
        if (closed || settle != null) return;
        float elapsedMs = lastFrameNanos == 0 ? 8f
                : Math.min(34f, (timeNanos - lastFrameNanos) / 1_000_000f);
        lastFrameNanos = timeNanos;
        float blend = (float) (1d - Math.exp(-elapsedMs / 31f));
        float next = amount + (targetAmount - amount) * blend;
        if (Math.abs(targetAmount - next) < 0.004f) next = targetAmount;
        if (apply(next) && next != targetAmount) queueFrame();
        else lastFrameNanos = 0;
    }

    void release() {
        if (!canAnimate()) return;
        stopFollowing();
        row.removeCallbacks(idleReset);
        if (!ownsMatrix || amount < 0.015f) {
            close();
            return;
        }
        animateToRest(ValueAnimator.ofFloat(amount, 0f), SETTLE_MS);
    }

    private void animateToRest(ValueAnimator rebound, long duration) {
        settle = rebound;
        settle.setDuration(duration);
        settle.setInterpolator(new PathInterpolator(0.20f, 0.60f, 0.24f, 1f));
        settle.addUpdateListener(animation -> apply((float) animation.getAnimatedValue()));
        settle.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                close();
            }
        });
        settle.start();
    }

    private boolean apply(float value) {
        Matrix current = row.getAnimationMatrix();
        if (ownsMatrix ? !matrix.equals(current) : current != null && !current.isIdentity()) {
            ownsMatrix = false;
            close();
            return false;
        }
        amount = value;
        float left = row == card ? 0f : card.getX();
        float top = row == card ? 0f : card.getY();
        float centerX = left + card.getWidth() * 0.5f;
        matrix.setScale(1f - value * MAX_NARROWING, 1f + value * MAX_STRETCH,
                centerX, top + card.getHeight() * 0.18f);
        matrix.postTranslate(0f, value * MAX_TRAVEL_DP * density);
        row.setAnimationMatrix(matrix);
        ownsMatrix = true;
        return true;
    }

    private void close() {
        if (closed) return;
        closed = true;
        stopFollowing();
        row.removeCallbacks(idleReset);
        row.removeOnAttachStateChangeListener(this);
        if (settle != null) {
            settle.removeAllUpdateListeners();
            settle.removeAllListeners();
            settle.cancel();
            settle = null;
        }
        if (ownsMatrix && matrix.equals(row.getAnimationMatrix())) row.setAnimationMatrix(null);
        ownsMatrix = false;
        WeakReference<PullDeformation> reference = active.get(row);
        if (reference != null && reference.get() == this) active.remove(row);
    }

    private void stopFollowing() {
        if (frameQueued) choreographer.removeFrameCallback(followFrame);
        frameQueued = false;
        lastFrameNanos = 0;
    }

    @Override
    public void onViewAttachedToWindow(View view) {}

    @Override
    public void onViewDetachedFromWindow(View view) {
        close();
    }
}
