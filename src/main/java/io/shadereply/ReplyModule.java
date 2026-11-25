package io.shadereply;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.PathInterpolator;

import io.github.libxposed.api.XposedModule;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class ReplyModule extends XposedModule {
    private static final String TAG = "WeChatShadeReply";
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String OPLUS_HEADS_UP = "com.oplus.systemui.notification.headsup.windowframe.";
    private static final String OPLUS_NOTIFICATION_CONTENT =
            "com.oplus.systemui.statusbar.notification.row.NotificationContentViewExtImp";
    private static final String OPLUS_HEADS_UP_TOUCH = OPLUS_HEADS_UP + "OplusHeadsUpTouchHelper";
    private static final int REMOTE_INPUT_TAG = NotificationResources.id("remote_input_tag");
    private static final int ACTION_INDEX_TAG = NotificationResources.id("notification_action_index_tag");
    private static final int NOTIFICATION_CONTENT = NotificationResources.id("status_bar_latest_event_content");
    private static final int ACTIONS_CONTAINER_LAYOUT = NotificationResources.id("actions_container_layout");
    private static final int[] HISTORY_CONTENT = {
        NotificationResources.id("notification_messaging"), NotificationResources.id("conversation_text"),
        NotificationResources.id("big_text"), NotificationResources.id("notification_main_column")
    };
    private static final int OPEN_RETRIES = 8;
    private static final int BANNER_LAYOUT_RETRIES = 12;
    private static final int MIN_PULL_MS = 85;
    private static final int MAX_COLLAPSE_DRAG_DP = 56;
    private static final float COLLAPSE_COMMIT_FRACTION = 0.60f;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final WeakHashMap<Object, NotificationGesture> bannerGestures = new WeakHashMap<>();
    private final WeakHashMap<View, BannerHeightPreview> bannerPreviews = new WeakHashMap<>();
    private final WeakHashMap<View, OpenReplyPull> openReplyPulls = new WeakHashMap<>();
    private final WeakHashMap<View, BannerSwipeUp> bannerSwipes = new WeakHashMap<>();
    private final WeakHashMap<View, String> compactSwipeKeys = new WeakHashMap<>();
    private final WeakHashMap<View, BannerSize> compactBannerSizes = new WeakHashMap<>();
    private final WeakHashMap<View, CompactActions> compactBannerActions = new WeakHashMap<>();
    private boolean replayingOpenReplyDown;
    private NotificationGesture shadeGesture;
    private WeakReference<View> shadeReplyRow = new WeakReference<>(null);
    private String shadeReplyKey;
    private Class<?> rowType;
    private Method getEntry, getSbn, getLayout, getChild, getEditor, getHeadsUpChild,
            isEditorActive, defocusEditor, forceEditorDefocus, isHeadsUp, activate, isInputActive, setUserExpanded,
            isPinned, notifyHeightChanged, isShowingExpanded;
    private Field privateRow, headsUpEditor, editorText, stackController, stackView, stackExpanded, statusBarState;
    private Field expandedWhenPinned, editorResetting, editorSending;
    private Method getActualHeight, getMaxContentHeight, getHeadsUpHeight, setActualHeight,
            setSwipingToExpand, isGroupSummary, removeHeadsUp;
    private Method removeHeadsUpWithoutAnimation;
    private Method setRemoteInputVisible;
    private Field rowHeadsUpManager;
    private Field swipingToExpand;
    private HeadsUpTimeout headsUpTimeout;
    private Object panel, remoteInputManager;
    private boolean installed, loggedError;
    private boolean observingConfig;
    private volatile GestureConfig config = GestureConfig.DEFAULT;
    private int requestId;

    private static final class NotificationGesture {
        final View row;
        final String key;
        final float x;
        final float y;
        final int slop;
        final float pullDistance;
        final long downTime;
        final boolean pullEnabled;
        final int holdMs;
        boolean handled;
        boolean cancelledNativeTouch;
        boolean pullCancelled;
        Runnable hold;
        final float previewStart;
        PullDeformation deformation;
        BannerHeightPreview bannerPreview;
        ReplyBackdrop backdrop;
        BannerSwipeUp swipeUp;
        float peakY;

        NotificationGesture(Candidate target, MotionEvent down, GestureConfig settings, int pullDp) {
            this(target.row(), target.notification().getKey(), down, settings, pullDp);
        }

        NotificationGesture(View row, String key, MotionEvent down, GestureConfig settings, int pullDp) {
            this.row = row;
            this.key = key;
            this.x = down.getRawX();
            this.y = down.getRawY();
            this.peakY = this.y;
            this.downTime = down.getDownTime();
            this.pullEnabled = settings.pull();
            this.holdMs = settings.hold() ? settings.holdMs() : 0;
            this.slop = ViewConfiguration.get(row.getContext()).getScaledTouchSlop();
            this.previewStart = slop;
            float density = row.getResources().getDisplayMetrics().density;
            this.pullDistance = Math.max(slop * 2f, pullDp * density);
        }

        boolean isDownwardPull(MotionEvent event, float minimumDistance) {
            float dx = Math.abs(event.getRawX() - x);
            float dy = event.getRawY() - y;
            return event.getPointerCount() == 1 && dy >= minimumDistance && dy > dx * 1.55f;
        }

        boolean confirmsPull(MotionEvent event) {
            return pullEnabled && event.getEventTime() - downTime >= MIN_PULL_MS
                    && isDownwardPull(event, pullDistance);
        }

        boolean reachesBannerThreshold(MotionEvent event) {
            return cancelledNativeTouch && !handled && !pullCancelled && pullEnabled
                    && isDownwardPull(event, pullDistance);
        }

        boolean shouldStartBannerPreview(MotionEvent event) {
            if (handled || pullCancelled || !pullEnabled || bannerPreview != null || deformation != null
                    || event.getPointerCount() != 1) return false;
            return isDownwardPull(event, slop);
        }

        boolean movedPastHold(MotionEvent event) {
            return event.getPointerCount() != 1 || Math.hypot(
                    event.getRawX() - x, event.getRawY() - y) > slop;
        }

        boolean isOtherGesture(MotionEvent event) {
            float dx = Math.abs(event.getRawX() - x);
            float dy = event.getRawY() - y;
            return event.getPointerCount() != 1 || dy < -slop || (dx > slop && dx >= dy);
        }

        void startPull(View surface) {
            if (deformation == null) deformation = new PullDeformation(surface, row);
            startBackdrop();
        }

        void startBackdrop() {
            backdrop = ReplyBackdrop.forView(row);
            if (backdrop != null) backdrop.startDrag(row);
        }

        void updatePull(MotionEvent event) {
            float distance = !pullCancelled && isDownwardPull(event, slop) ? event.getRawY() - y : 0f;
            if (bannerPreview != null) bannerPreview.follow(distance, previewStart, pullDistance);
            if (deformation != null) {
                deformation.followBannerDrag(distance - previewStart);
                if (backdrop != null) backdrop.drag(bannerProgress(distance, previewStart, pullDistance));
            }
        }

        void endMotion() {
            if (swipeUp != null) {
                swipeUp.release(true);
                swipeUp = null;
            }
            if (bannerPreview != null) bannerPreview.finish(handled);
            if (deformation != null) deformation.release();
            if (!handled && backdrop != null && bannerPreview == null) backdrop.collapse();
            bannerPreview = null;
            deformation = null;
            backdrop = null;
        }

        boolean followsSwipeUp(MotionEvent event) {
            if (event.getPointerCount() != 1) return false;
            peakY = Math.max(peakY, event.getRawY());
            if (!handled || pullCancelled) return false;
            float up = peakY - event.getRawY();
            return up >= slop && up > Math.abs(event.getRawX() - x) * 1.55f;
        }

        boolean handleSwipeUp(MotionEvent event) {
            if (swipeUp == null) return false;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP)
                swipeUp.follow(peakY - event.getRawY() - slop);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                    || action == MotionEvent.ACTION_POINTER_DOWN) {
                swipeUp.release(action != MotionEvent.ACTION_UP);
                swipeUp = null;
            }
            return true;
        }
    }

    private static float bannerProgress(float distance, float start, float threshold) {
        float progress = Math.max(0f, Math.min(1f, (distance - start) / Math.max(1f, threshold - start)));
        return progress * progress * (2f - progress);
    }

    private record ReplyAction(int index, Notification.Action action, RemoteInput input) {}
    private record Candidate(View row, StatusBarNotification notification, List<ReplyAction> replies) {}
    private record ReplyControl(View anchor, ReplyAction reply) {}
    private record BannerSize(String key, int height) {}
    private record CompactActions(String key, int width, int height, BannerTemplateTransition.ActionStrip strip) {}
    private record BannerTemplates(View compact, View expanded, View replyButton, Notification notification,
            BannerTemplateTransition.ActionStrip actions) {}
    private record ShadeReplySwitch(View target, String key, View previous) {}

    private int compactHeight(View card, String key, int fallback) {
        BannerSize size = compactBannerSizes.get(card);
        return size != null && size.key().equals(key) ? size.height() : fallback;
    }

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        WeChatNotifications.onModuleLoaded(param.getProcessName());
        if (WeChatNotifications.PACKAGE.equals(param.getProcessName())
                || (WeChatNotifications.PACKAGE + ":push").equals(param.getProcessName())) {
            log(Log.INFO, TAG, "ShadeReply loaded in " + param.getProcessName(), null);
        }
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (WeChatNotifications.PACKAGE.equals(param.getPackageName())) {
            WeChatNotifications.onPackageReady(this, param.getClassLoader());
            return;
        }
        if (installed || !param.isFirstPackage() || !SYSTEM_UI.equals(param.getPackageName())) return;
        ClassLoader loader = param.getClassLoader();
        int sdk = Build.VERSION.SDK_INT;
        if (sdk != 37 && (sdk != Build.VERSION_CODES.BAKLAVA || !hasColorOsSystemUi(loader))) {
            log(Log.INFO, TAG, "SystemUI hooks skipped for SDK " + sdk, null);
            return;
        }
        installed = true;
        try {
            install(loader);
            log(Log.INFO, TAG, "ColorOS " + (sdk == 37 ? "17" : "16") + " hooks installed", null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            log(Log.WARN, TAG, "SystemUI methods unavailable on SDK " + sdk, error);
        }
    }

    private static boolean hasColorOsSystemUi(ClassLoader loader) {
        for (String name : new String[] {OPLUS_NOTIFICATION_CONTENT, OPLUS_HEADS_UP_TOUCH}) {
            try {
                Class.forName(name, false, loader);
                return true;
            } catch (ClassNotFoundException | LinkageError ignored) {
                // ColorOS builds can include different notification extensions.
            }
        }
        return false;
    }

    private void install(ClassLoader loader) throws ReflectiveOperationException {
        String status = SYSTEM_UI + ".statusbar.";
        String notification = status + "notification.";
        String shade = SYSTEM_UI + ".shade.";
        rowType = loader.loadClass(notification + "row.ExpandableNotificationRow");
        Class<?> entry = loader.loadClass(notification + "collection.NotificationEntry");
        Class<?> content = loader.loadClass(notification + "row.NotificationContentView");
        Class<?> editor = loader.loadClass(status + "policy.RemoteInputView");
        Class<?> manager = loader.loadClass(status + "NotificationRemoteInputManager");
        Class<?> panelType = loader.loadClass(shade + "NotificationPanelViewController");
        Class<?> controller = loader.loadClass(notification + "stack.NotificationStackScrollLayoutController");
        Class<?> stack = loader.loadClass(notification + "stack.NotificationStackScrollLayout");

        try {
            getEntry = method(rowType, "getEntry");
        } catch (NoSuchMethodException unavailable) {
            getEntry = method(rowType, "getEntryLegacy");
        }
        getSbn = method(entry, "getSbn");
        getLayout = method(rowType, "getPrivateLayout");
        getChild = method(content, "getExpandedChild");
        getEditor = method(content, "getExpandedRemoteInput");
        getHeadsUpChild = method(content, "getHeadsUpChild");
        isEditorActive = method(editor, "isActive");
        guarded(() -> editorResetting = field(editor, "mResetting"));
        guarded(() -> editorSending = field(editor, "mSending"));
        editorText = field(editor, "mEditText");
        defocusEditor = method(editorText.getType(), "defocusIfNeeded", boolean.class);
        guarded(() -> forceEditorDefocus = method(editor, "onDefocus", boolean.class, boolean.class, Runnable.class));
        isHeadsUp = method(rowType, "isHeadsUp");
        isInputActive = method(manager, "isRemoteInputActive");
        setUserExpanded = method(rowType, "setUserExpanded", boolean.class);
        isShowingExpanded = method(rowType, "isShowingExpanded");
        activate = method(manager, "activateRemoteInput", View.class, RemoteInput[].class,
                RemoteInput.class, android.app.PendingIntent.class,
                loader.loadClass(entry.getName() + "$EditedSuggestionInfo"));
        privateRow = field(rowType, "mShowingPublic");
        headsUpEditor = field(content, "mHeadsUpRemoteInput");
        stackController = field(panelType, "mNotificationStackScrollLayoutController");
        stackView = field(controller, "mView");
        stackExpanded = field(stack, "mIsExpanded");
        statusBarState = field(stack, "mStatusBarState");
        Method longPress = method(rowType, "doLongClickCallback", int.class, int.class);
        Method dispatchTouch = method(stack, "dispatchTouchEvent", MotionEvent.class);
        Method childAtTouch = method(stack, "getChildAtRawPosition", float.class, float.class);

        for (var ctor : manager.getDeclaredConstructors()) {
            hook(ctor).intercept(chain -> {
                Object result = chain.proceed();
                remoteInputManager = chain.getThisObject();
                observeConfig((Context) chain.getArg(0));
                return result;
            });
        }
        for (var ctor : panelType.getDeclaredConstructors()) {
            hook(ctor).intercept(chain -> {
                Object result = chain.proceed();
                panel = chain.getThisObject();
                return result;
            });
        }
        hook(longPress).intercept(chain -> {
            View row = (View) chain.getThisObject();
            if (shadeGesture != null && shadeGesture.row == row)
                return shadeGesture.holdMs > 0 ? null : chain.proceed();
            for (NotificationGesture gesture : bannerGestures.values())
                if (gesture.row == row)
                    return gesture.holdMs > 0 ? null : chain.proceed();
            return chain.proceed();
        });
        hook(dispatchTouch).intercept(chain -> handleShadeTouch(chain, childAtTouch));
        hook(activate).intercept(this::switchShadeReply);
        installOpenReplyPulls(loader, childAtTouch);
        try {
            NotificationMedia.install(this, loader);
            log(Log.INFO, TAG, "Notification media sizing installed", null);
        } catch (ReflectiveOperationException | RuntimeException error) {
            log(Log.WARN, TAG, "Notification media sizing unavailable", error);
        }

        try {
            isPinned = method(rowType, "isPinned");
            try {
                notifyHeightChanged = method(rowType, "notifyHeightChanged", String.class, boolean.class);
            } catch (NoSuchMethodException unavailable) {
                notifyHeightChanged = method(rowType, "notifyHeightChanged", boolean.class);
            }
            expandedWhenPinned = field(rowType, "mExpandedWhenPinned");
            getHeadsUpHeight = method(rowType, "getHeadsUpHeight");
            rowHeadsUpManager = field(rowType, "mHeadsUpManager");
            removeHeadsUp = method(rowHeadsUpManager.getType(), "removeNotification",
                    String.class, boolean.class, String.class);
            guarded(() -> removeHeadsUpWithoutAnimation = method(rowHeadsUpManager.getType(), "removeNotification",
                    String.class, boolean.class, boolean.class, String.class));
        } catch (ReflectiveOperationException | RuntimeException error) {
            log(Log.WARN, TAG, "Banner expansion unavailable", error);
        }
        try {
            Class<?> expandable = loader.loadClass(notification + "row.ExpandableView");
            getActualHeight = method(expandable, "getActualHeight");
            getMaxContentHeight = method(rowType, "getMaxContentHeight");
            setActualHeight = method(rowType, "setActualHeight", int.class, boolean.class);
            hook(setActualHeight).intercept(chain -> {
                // The stack animator may still write the pre-gesture height.
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    Object row = chain.getThisObject();
                    BannerSwipeUp swipe = bannerSwipes.get(row);
                    if (swipe != null && swipe.ownsRowHeight()) return null;
                    BannerHeightPreview preview = bannerPreviews.get(row);
                    if (preview != null && !preview.writingHeight) return null;
                }
                return chain.proceed();
            });
            isGroupSummary = method(rowType, "isSummaryWithChildren");
            try {
                setSwipingToExpand = method(rowType, "setUserSwipingToExpandRow", boolean.class);
                swipingToExpand = field(rowType, "mIsUserSwipingToExpandRow");
            } catch (NoSuchMethodException | NoSuchFieldException unavailable) {
                if (Build.VERSION.SDK_INT != Build.VERSION_CODES.BAKLAVA) throw unavailable;
                setSwipingToExpand = method(rowType, "setUserLocked", boolean.class);
                swipingToExpand = field(rowType, "mUserLocked");
            }
            log(Log.INFO, TAG, "ColorOS banner drag preview available", null);
        } catch (ReflectiveOperationException | RuntimeException error) {
            log(Log.WARN, TAG, "Banner height preview unavailable", error);
        }
        try {
            setRemoteInputVisible = method(content, "setRemoteInputVisible", boolean.class);
        } catch (ReflectiveOperationException error) {
            log(Log.WARN, TAG, "Banner action visibility refresh unavailable", error);
        }
        try {
            headsUpTimeout = new HeadsUpTimeout(loader);
            log(Log.INFO, TAG, "ColorOS banner touch timeout reset available", null);
        } catch (ReflectiveOperationException | RuntimeException error) {
            log(Log.WARN, TAG, "Banner touch timeout reset unavailable", error);
        }
        installHistoryExpandClick(loader);
        installBanner(loader, SYSTEM_UI + ".statusbar.notification.headsup.HeadsUpTouchHelper");
        installBanner(loader, OPLUS_HEADS_UP_TOUCH);
        try {
            new FullScreenReply(loader, entry);
            log(Log.INFO, TAG, "ColorOS fullscreen banner hooks installed", null);
        } catch (ReflectiveOperationException | RuntimeException error) {
            log(Log.WARN, TAG, "Fullscreen banner hooks unavailable", error);
        }
    }

    private interface OpenReplyTarget {
        OpenReplyDrag find(View window, MotionEvent down) throws ReflectiveOperationException;
    }

    private record OpenReplyDrag(View surface, View card, BannerSwipeUp swipeUp, boolean preventDismiss,
            Candidate reopen) {
        private OpenReplyDrag(View surface, View card, BannerSwipeUp swipeUp, boolean preventDismiss) {
            this(surface, card, swipeUp, preventDismiss, null);
        }

        private static final OpenReplyDrag KEEP_EDITOR_OPEN = new OpenReplyDrag(null, null, null, false);
    }

    private final class OpenReplyPull implements View.OnAttachStateChangeListener {
        final View window;
        final OpenReplyDrag target;
        final float x, y;
        final int slop;
        final NotificationGesture reopen;
        final Runnable releaseToNative = this::replayDown;
        MotionEvent down;
        PullDeformation deformation;
        boolean swipingUp, cancelled;

        OpenReplyPull(View window, MotionEvent event, OpenReplyDrag target) {
            this.window = window;
            this.target = target;
            x = event.getRawX();
            y = event.getRawY();
            slop = ViewConfiguration.get(window.getContext()).getScaledTouchSlop();
            GestureConfig settings = config;
            reopen = target.reopen() == null ? null
                    : new NotificationGesture(target.reopen(), event, settings, settings.bannerDp());
            down = MotionEvent.obtain(event);
            window.addOnAttachStateChangeListener(this);
            if (!target.preventDismiss())
                main.postAtTime(releaseToNative, event.getDownTime() + ViewConfiguration.getLongPressTimeout());
        }

        boolean handle(MotionEvent event) {
            int action = event.getActionMasked();
            if (cancelled) {
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) finish();
                return true;
            }
            float dx = Math.abs(event.getRawX() - x);
            float dy = event.getRawY() - y;
            boolean pulling = config.pull() && target.card() != null
                    && event.getPointerCount() == 1 && dy > dx * 1.55f;
            if (down != null) {
                boolean upward = (target.swipeUp() != null || target.preventDismiss())
                        && event.getPointerCount() == 1 && -dy >= slop && -dy > dx;
                if (action == MotionEvent.ACTION_MOVE && (upward || pulling && dy >= slop)) {
                    // ColorOS must not get any part of this drag, including a synthetic CANCEL.
                    swipingUp = upward;
                    discardDown();
                } else if (action == MotionEvent.ACTION_CANCEL) {
                    finish();
                    return true;
                } else if (action != MotionEvent.ACTION_MOVE || event.getPointerCount() != 1
                        || Math.abs(dy) >= slop || (dx > slop && dx >= dy)) {
                    replayDown();
                    return false;
                } else {
                    return true;
                }
            }
            if (swipingUp) {
                BannerSwipeUp swipe = target.swipeUp();
                if (swipe != null && (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP))
                    swipe.follow(-dy - slop);
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                        || action == MotionEvent.ACTION_POINTER_DOWN) {
                    if (swipe != null) swipe.release(action != MotionEvent.ACTION_UP);
                    swipingUp = false;
                    if (action == MotionEvent.ACTION_POINTER_DOWN) cancelled = true;
                    else finish();
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (action == MotionEvent.ACTION_UP) reopenEditorOnPull(event);
                finish();
            } else if (action == MotionEvent.ACTION_MOVE && event.getPointerCount() == 1
                    && target.surface() != null && !reopenEditorOnPull(event)) {
                if (deformation == null) deformation = new PullDeformation(target.surface(), target.card());
                deformation.follow(pulling ? dy : 0f);
            } else if (action == MotionEvent.ACTION_POINTER_DOWN && reopen != null) {
                reopen.pullCancelled = true;
            }
            return true;
        }

        private boolean reopenEditorOnPull(MotionEvent event) {
            if (reopen == null) return false;
            if (reopen.handled) return true;
            if (!reopen.pullEnabled || reopen.pullCancelled || !reopen.isDownwardPull(event, reopen.pullDistance))
                return false;
            reopen.handled = tryBannerReply(reopen.row, reopen.key);
            if (reopen.handled) {
                if (deformation != null) {
                    deformation.release();
                    deformation = null;
                }
                if (headsUpTimeout != null && rowHeadsUpManager != null)
                    guarded(() -> headsUpTimeout.reset(rowHeadsUpManager.get(reopen.row), reopen.key));
            }
            return reopen.handled;
        }

        private void discardDown() {
            main.removeCallbacks(releaseToNative);
            if (down != null) {
                down.recycle();
                down = null;
            }
        }

        private void replayDown() {
            MotionEvent original = down;
            down = null;
            finish();
            if (original == null) return;
            boolean wasReplaying = replayingOpenReplyDown;
            try {
                replayingOpenReplyDown = true;
                if (window.isAttachedToWindow()) window.dispatchTouchEvent(original);
            } finally {
                replayingOpenReplyDown = wasReplaying;
                original.recycle();
            }
        }

        private void finish() {
            discardDown();
            openReplyPulls.remove(window);
            window.removeOnAttachStateChangeListener(this);
            if (deformation != null) deformation.release();
            if (swipingUp && target.swipeUp() != null) target.swipeUp().release(true);
            swipingUp = false;
        }

        @Override public void onViewAttachedToWindow(View view) {}
        @Override public void onViewDetachedFromWindow(View view) { finish(); }
    }

    private boolean handleOpenReplyPull(View window, MotionEvent event, OpenReplyTarget target) {
        if (replayingOpenReplyDown) return false;
        OpenReplyPull pull = openReplyPulls.get(window);
        if (event.getActionMasked() != MotionEvent.ACTION_DOWN)
            return pull != null && pull.handle(event);
        if (pull != null) pull.finish();
        if (event.getPointerCount() != 1 || !unlocked(window)) return false;
        guarded(() -> {
            OpenReplyDrag drag = target.find(window, event);
            if (drag != null && (config.pull() || drag.swipeUp() != null || drag.preventDismiss()))
                openReplyPulls.put(window, new OpenReplyPull(window, event, drag));
        });
        return openReplyPulls.containsKey(window);
    }

    private void installOpenReplyPulls(ClassLoader loader, Method childAtTouch) {
        guarded(() -> {
            Class<?> shade = loader.loadClass(SYSTEM_UI + ".shade.NotificationShadeWindowView");
            Method dispatch = method(shade, "dispatchTouchEvent", MotionEvent.class);
            hook(dispatch).intercept(chain -> handleOpenReplyPull((View) chain.getThisObject(),
                    (MotionEvent) chain.getArg(0), (window, down) -> {
                        ViewGroup list = stack();
                        if (list == null || !list.isShown() || !isDescendantOf(list, window)) return null;
                        Object row = childAtTouch.invoke(list, down.getRawX(), down.getRawY());
                        if (!rowType.isInstance(row)) return null;
                        boolean inShade = shadeReady(list);
                        if (!inShade && !(boolean) isHeadsUp.invoke(row)) return null;
                        return protectOpenReply((View) row, down, !inShade);
                    }) ? true : chain.proceed());
        });
        guarded(() -> {
            Class<?> banner = loader.loadClass(OPLUS_HEADS_UP + "HeadsUpLayout");
            Method dispatch = method(banner, "dispatchTouchEvent", MotionEvent.class);
            Method getRow = method(banner, "getRow");
            hook(dispatch).intercept(chain -> handleOpenReplyPull((View) chain.getThisObject(),
                    (MotionEvent) chain.getArg(0), (window, down) -> {
                        Object row = getRow.invoke(window);
                        return rowType.isInstance(row) ? protectOpenReply((View) row, down, true) : null;
                    }) ? true : chain.proceed());
        });
    }

    private OpenReplyDrag protectOpenReply(View row, MotionEvent down, boolean animate)
            throws ReflectiveOperationException {
        if (!touches(row, down) || privateRow.getBoolean(row)) return null;
        View editor = visibleEditor(row);
        boolean editing = editor != null && (boolean) isEditorActive.invoke(editor);
        if (editing && touches(editor, down)) return null;
        if (!animate) return editing ? OpenReplyDrag.KEEP_EDITOR_OPEN : null;
        BannerSwipeUp swipe = rowSwipeUp(row, true);
        boolean expanded = editor != null || expandedWhenPinned != null && expandedWhenPinned.getBoolean(row);
        if (expanded) {
            Candidate reopen = editing ? null : bannerCandidate(row, null);
            if (!editing && reopen == null) return null;
            return new OpenReplyDrag(row, row, swipe, true, reopen);
        }
        return swipe == null ? null : new OpenReplyDrag(null, null, swipe, false);
    }

    private BannerSwipeUp rowSwipeUp(View row, boolean allowCompact) throws ReflectiveOperationException {
        if (getHeadsUpHeight == null || removeHeadsUp == null || getActualHeight == null
                || setActualHeight == null || bannerSwipes.containsKey(row)
                || !(boolean) isHeadsUp.invoke(row) || !(boolean) isPinned.invoke(row)) return null;
        Candidate target = bannerCandidate(row, null);
        if (target == null) return null;
        View editor = visibleEditor(row);
        boolean expanded = expandedWhenPinned.getBoolean(row) || editor != null;
        String key = target.notification().getKey();
        if (!expanded && (!allowCompact || !key.equals(compactSwipeKeys.get(row)))) return null;
        BannerHeightPreview opening = bannerPreviews.get(row);
        boolean interruptsOpening = opening != null && opening.opening;
        int height = (int) getActualHeight.invoke(row);
        int compact = expanded ? Math.min(height, compactHeight(row, key, (int) getHeadsUpHeight.invoke(row))) : height;
        Object content = expanded ? getLayout.invoke(row) : null;
        View expandedChild = content == null ? null : (View) getChild.invoke(content);
        View history = findHistory(expandedChild, editor);
        View compactChild = content == null ? null : (View) getHeadsUpChild.invoke(content);
        View actions = compactChild == null ? null : compactChild.findViewById(ACTIONS_CONTAINER_LAYOUT);
        View replyView = compactReplyButton(target, actions);
        Rect replyButton = compactReplyBounds(row, replyView, actions, compact);
        Object manager = rowHeadsUpManager.get(row);
        if (manager == null) return null;
        BooleanSupplier current = () -> {
            try {
                if (!row.isShown() || privateRow.getBoolean(row) || !(boolean) isHeadsUp.invoke(row)) return false;
                Object entry = getEntry.invoke(row);
                return entry != null && getSbn.invoke(entry) instanceof StatusBarNotification notification
                        && key.equals(notification.getKey());
            } catch (ReflectiveOperationException | RuntimeException error) {
                warn(error);
                return false;
            }
        };
        BannerHeight heightControl = new BannerHeight() {
            private boolean swiping;

            @Override public void start() throws ReflectiveOperationException {
                if (setSwipingToExpand != null && swipingToExpand != null && !swipingToExpand.getBoolean(row)) {
                    setSwipingToExpand.invoke(row, true);
                    swiping = true;
                }
            }

            @Override public void set(int value) throws ReflectiveOperationException {
                setActualHeight.invoke(row, value, false);
            }

            @Override public void restore(boolean committed) throws ReflectiveOperationException {
                try {
                    if (!committed && row.isAttachedToWindow() && current.getAsBoolean()) set(height);
                } finally {
                    if (swiping) setSwipingToExpand.invoke(row, false);
                }
                if (committed && current.getAsBoolean()) {
                    // Recompute the compact template action offset after the native layout switch.
                    set(compact);
                    Object layout = getLayout.invoke(row);
                    if (layout != null && setRemoteInputVisible != null && visibleEditor(row) == null) {
                        // ColorOS restores the actions on the currently visible template only.
                        setRemoteInputVisible.invoke(layout, false);
                    }
                    notifyBannerHeight(row, "ShadeReply collapse pinned banner", false);
                } else if (interruptsOpening && current.getAsBoolean()) {
                    notifyBannerHeight(row, "ShadeReply resume pinned expansion", true);
                }
            }
        };
        BannerSwipeUp swipe = newBannerSwipe(row, row, editor, history, actions, replyButton, height, compact, expanded, heightControl, current,
                () -> headsUpTimeout == null ? () -> {} : headsUpTimeout.pause(manager, key), () -> {
            ++requestId;
            // An arriving message can replace the layout or move the editor into its compact template.
            Object layout = getLayout.invoke(row);
            if (layout != null) {
                View expandedInput = (View) getEditor.invoke(layout);
                View compactInput = (View) headsUpEditor.get(layout);
                closeBannerEditor(expandedInput);
                if (compactInput != expandedInput) closeBannerEditor(compactInput);
            }
            expandedWhenPinned.setBoolean(row, false);
            compactSwipeKeys.put(row, key);
        }, finished -> {
            compactSwipeKeys.remove(row);
            if (removeHeadsUpWithoutAnimation != null) {
                boolean removed = (boolean) removeHeadsUpWithoutAnimation.invoke(manager, key, true, false,
                        "ShadeReply swipe up");
                if (removed) row.postOnAnimation(() -> row.postOnAnimation(finished));
            } else {
                removeHeadsUp.invoke(manager, key, true, "ShadeReply swipe up");
            }
        },
                backdrop -> { if (editor != null) watchEditorBackdrop(backdrop, editor); });
        if (swipe != null) {
            if (interruptsOpening) {
                swipe.templateStart = opening.progressAt(height);
                if (opening.replyOrigin != null) opening.replyOrigin.finish();
                opening.close(true);
            }
            if (compactChild != null && expandedChild != null)
                swipe.templates = new BannerTemplates(compactChild, expandedChild, replyView,
                        target.notification().getNotification(), compactActionsForTransition(row, key, actions, compact));
        }
        return swipe;
    }

    private interface BannerHeight {
        default void start() throws ReflectiveOperationException {}
        void set(int height) throws ReflectiveOperationException;
        void restore(boolean committed) throws ReflectiveOperationException;
    }

    private interface PauseBannerTimer {
        CheckedAction pause() throws ReflectiveOperationException;
    }

    private interface DismissBanner {
        void dismiss(Runnable finished) throws ReflectiveOperationException;
    }

    private BannerSwipeUp newBannerSwipe(View card, View surface, View editor, View history, View actions,
            Rect replyButton, int height, int compact,
            boolean expanded, BannerHeight heightControl, BooleanSupplier current, PauseBannerTimer timer,
            CheckedAction collapse, DismissBanner dismiss,
            Consumer<ReplyBackdrop> restoreBlur) {
        if (height <= 0 || compact <= 0) return null;
        return new BannerSwipeUp(card, surface, editor, history, actions, replyButton,
                height, Math.min(compact, height), expanded,
                heightControl, current, timer, collapse, dismiss, restoreBlur);
    }

    private final class BannerSwipeUp implements View.OnAttachStateChangeListener {
        private final View card, surface, editor, history, actions;
        private final Rect replyButton;
        private final int expanded, compact;
        private final float collapseDistance, dismissDistance;
        private final BooleanSupplier current;
        private final CheckedAction collapse;
        private final DismissBanner dismiss;
        private final PauseBannerTimer timer;
        private final Consumer<ReplyBackdrop> restoreBlur;
        private final BannerHeight heightControl;
        private final Choreographer choreographer = Choreographer.getInstance();
        private final Choreographer.FrameCallback followFrame = this::onFrame;
        private final Runnable finishDismissal = this::restore;
        private int renderedHeight;
        private float originalTranslation, offset, blurProgress, distance, targetDistance, exitDistance;
        private float cardAlpha, templateStart = 1f;
        private long lastFrameNanos;
        private ReplyBackdrop backdrop;
        private ValueAnimator settle;
        private BannerCollapseContent collapseContent;
        private BannerTemplates templates;
        private BannerTemplateTransition templateTransition;
        private CheckedAction resumeTimer;
        private boolean started, finished, collapsed, writingHeight, frameQueued, dismissing;

        BannerSwipeUp(View card, View surface, View editor, View history, View actions,
                Rect replyButton, int expanded, int compact, boolean startsExpanded,
                BannerHeight heightControl, BooleanSupplier current, PauseBannerTimer timer, CheckedAction collapse,
                DismissBanner dismiss, Consumer<ReplyBackdrop> restoreBlur) {
            this.card = card;
            this.surface = surface;
            this.editor = editor;
            this.history = history;
            this.actions = actions;
            this.replyButton = replyButton;
            this.expanded = expanded;
            this.compact = compact;
            this.heightControl = heightControl;
            renderedHeight = expanded;
            this.current = current;
            this.timer = timer;
            this.collapse = collapse;
            this.dismiss = dismiss;
            this.restoreBlur = restoreBlur;
            float density = card.getResources().getDisplayMetrics().density;
            collapseDistance = startsExpanded
                    ? Math.max(1f, Math.min(expanded - compact, MAX_COLLAPSE_DRAG_DP * density)) : 0f;
            dismissDistance = Math.max(34f * density, Math.min(64f * density, compact * 0.48f));
        }

        void follow(float up) {
            if (finished) return;
            if (!current.getAsBoolean()) {
                restore();
                return;
            }
            if (!started) start();
            targetDistance = Math.min(Math.max(0f, up), collapseDistance > 0f ? collapseDistance : exitDistance);
            if (collapseDistance == 0f || !ValueAnimator.areAnimatorsEnabled()) {
                render(targetDistance);
            } else if (distance != targetDistance) {
                queueFrame();
            }
        }

        private void queueFrame() {
            if (frameQueued) return;
            frameQueued = true;
            choreographer.postFrameCallback(followFrame);
        }

        private void onFrame(long timeNanos) {
            frameQueued = false;
            if (finished || settle != null) return;
            if (!current.getAsBoolean()) {
                restore();
                return;
            }
            float elapsedMs = lastFrameNanos == 0 ? 8f : Math.min(24f, (timeNanos - lastFrameNanos) / 1_000_000f);
            lastFrameNanos = timeNanos;
            float blend = (float) (1d - Math.exp(-elapsedMs / 18f));
            float next = distance + (targetDistance - distance) * blend;
            render(Math.abs(targetDistance - next) < 0.25f ? targetDistance : next);
            if (!finished && distance != targetDistance) queueFrame();
            else lastFrameNanos = 0;
        }

        private void stopFollowing() {
            if (frameQueued) choreographer.removeFrameCallback(followFrame);
            frameQueued = false;
            lastFrameNanos = 0;
        }

        boolean ownsRowHeight() {
            return started && !finished && collapseDistance > 0f && !writingHeight;
        }

        private void start() {
            bannerSwipes.put(card, this);
            started = true;
            guarded(() -> resumeTimer = timer.pause());
            originalTranslation = surface.getTranslationY();
            if (collapseDistance > 0f) {
                if (templates != null) guarded(() -> templateTransition = BannerTemplateTransition.create(card,
                        templates.compact(), templates.expanded(), templates.replyButton(), replyButton,
                        templates.notification(), compact, expanded, actions, templates.actions(), false));
                boolean drawsActions = templateTransition != null && templates.actions() != null;
                collapseContent = new BannerCollapseContent(card, editor, history, drawsActions ? null : actions,
                        replyButton);
                if (templateTransition != null && templateStart < 1f)
                    templateTransition.render(templateStart, renderedHeight);
                guarded(heightControl::start);
            }
            int[] bounds = new int[2];
            card.getLocationOnScreen(bounds);
            exitDistance = Math.max(dismissDistance, bounds[1] + Math.max(compact, card.getHeight()) + 8f);
            backdrop = collapseDistance > 0f ? ReplyBackdrop.forView(card) : null;
            if (backdrop != null) blurProgress = backdrop.startDrag(card);
            card.addOnAttachStateChangeListener(this);
        }

        private void render(float up) {
            distance = up;
            if (collapseDistance == 0f) {
                renderTranslation(up);
                return;
            }
            float collapsed = up / collapseDistance;
            float progress = collapsed * collapsed * (3f - 2f * collapsed);
            int height = Math.round(expanded + (compact - expanded) * progress);
            if (collapseContent != null) collapseContent.reset();
            if (height != renderedHeight) {
                try {
                    writingHeight = true;
                    heightControl.set(height);
                    renderedHeight = height;
                } catch (ReflectiveOperationException | RuntimeException error) {
                    warn(error);
                    restore();
                    return;
                } finally {
                    writingHeight = false;
                }
            }
            if (collapseContent != null) collapseContent.render(progress, height);
            if (templateTransition != null) templateTransition.render(templateStart * (1f - progress), height);
            if (backdrop != null) backdrop.drag(blurProgress * (1f - progress));
        }

        private void renderTranslation(float up) {
            offset = Math.min(exitDistance, up);
            surface.setTranslationY(originalTranslation - offset);
        }

        void release(boolean cancelled) {
            if (!started || finished || settle != null) return;
            stopFollowing();
            boolean dismissCard = !cancelled && collapseDistance == 0f && offset >= dismissDistance;
            boolean close = !cancelled && collapseDistance > 0f
                    && targetDistance >= collapseDistance * COLLAPSE_COMMIT_FRACTION;
            float target = dismissCard ? exitDistance : close ? collapseDistance : 0f;
            if (distance == target || !ValueAnimator.areAnimatorsEnabled()) {
                complete(target, close, dismissCard);
                return;
            }
            settle = ValueAnimator.ofFloat(distance, target);
            settle.setDuration(dismissCard ? 180 : 220);
            settle.setInterpolator(new PathInterpolator(0.20f, 0.68f, 0.24f, 1f));
            settle.addUpdateListener(animation -> {
                if (current.getAsBoolean()) render((float) animation.getAnimatedValue());
                else restore();
            });
            settle.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) {
                    settle = null;
                    complete(target, close, dismissCard);
                }
            });
            settle.start();
        }

        private void complete(float target, boolean close, boolean dismissCard) {
            if (finished) return;
            if (!current.getAsBoolean()) {
                restore();
                return;
            }
            render(target);
            if (finished) return;
            if (collapseContent != null) collapseContent.reset();
            if (close) {
                try {
                    collapse.run();
                    collapsed = true;
                } catch (ReflectiveOperationException | RuntimeException error) {
                    warn(error);
                    restore();
                    return;
                }
            }
            if (dismissCard) {
                dismissing = true;
                // Keep the departing card out of the draw pass while SystemUI removes or repositions it.
                cardAlpha = card.getTransitionAlpha();
                card.setTransitionAlpha(0f);
                try {
                    dismiss.dismiss(() -> main.post(finishDismissal));
                    if (!finished) main.postDelayed(finishDismissal,
                            Math.max(700L, Math.round(420 * ValueAnimator.getDurationScale())));
                } catch (ReflectiveOperationException | RuntimeException error) {
                    warn(error);
                    restore();
                }
            } else {
                restore();
            }
        }

        void restore() {
            if (finished) return;
            finished = true;
            main.removeCallbacks(finishDismissal);
            if (bannerSwipes.get(card) == this) bannerSwipes.remove(card);
            if (!started) return;
            stopFollowing();
            if (settle != null) {
                settle.removeAllListeners();
                settle.removeAllUpdateListeners();
                settle.cancel();
                settle = null;
            }
            card.removeOnAttachStateChangeListener(this);
            if (collapseContent != null) collapseContent.finish();
            if (collapseDistance > 0f) guarded(() -> heightControl.restore(collapsed));
            if (templateTransition != null) templateTransition.finish();
            if (offset != 0f && surface.getTranslationY() == originalTranslation - offset)
                surface.setTranslationY(originalTranslation);
            if (dismissing && card.getTransitionAlpha() == 0f) card.setTransitionAlpha(cardAlpha);
            if (backdrop != null) {
                if (distance == 0f && current.getAsBoolean() && editor != null) restoreBlur.accept(backdrop);
                else backdrop.collapse();
            }
            if (resumeTimer != null) guarded(resumeTimer);
        }

        @Override public void onViewAttachedToWindow(View view) {}
        @Override public void onViewDetachedFromWindow(View view) { restore(); }
    }

    private static final class FullScreenBannerHeight implements BannerHeight {
        private final View card, container;
        private ViewGroup.LayoutParams cardParams, containerParams;
        private int cardLayoutHeight, containerLayoutHeight, extraHeight;

        FullScreenBannerHeight(View card, View container) {
            this.card = card;
            this.container = container;
        }

        @Override public void set(int height) {
            if (cardParams == null) {
                cardParams = card.getLayoutParams();
                containerParams = container.getLayoutParams();
                cardLayoutHeight = cardParams.height;
                containerLayoutHeight = containerParams.height;
                extraHeight = Math.max(0, container.getHeight() - card.getHeight());
            }
            // ColorOS draws the material, outline and spotlight from these two layout heights.
            cardParams.height = height;
            containerParams.height = height + extraHeight;
            card.requestLayout();
        }

        @Override public void restore(boolean committed) {
            if (cardParams == null) return;
            cardParams.height = cardLayoutHeight;
            containerParams.height = containerLayoutHeight;
            card.requestLayout();
        }
    }

    private void observeConfig(Context context) {
        if (observingConfig) return;
        observingConfig = true;
        Context app = context.getApplicationContext();
        Context source = app != null ? app : context;
        HandlerThread thread = new HandlerThread("WeChatReplySettings");
        thread.start();
        Handler worker = new Handler(thread.getLooper());
        worker.post(() -> {
            try {
                source.getContentResolver().registerContentObserver(GestureConfig.URI, false,
                        new ContentObserver(worker) {
                            @Override
                            public void onChange(boolean selfChange) {
                                reloadConfig(source);
                            }
                        });
                reloadConfig(source);
            } catch (RuntimeException error) {
                log(Log.WARN, TAG, "Settings observer unavailable", error);
                thread.quitSafely();
            }
        });
    }

    private void reloadConfig(Context context) {
        try {
            GestureConfig latest = GestureConfig.readFromProvider(context);
            if (latest != null) {
                config = latest;
                log(Log.INFO, TAG, "Gesture settings applied: " + latest, null);
            }
        } catch (RuntimeException error) {
            log(Log.WARN, TAG, "Could not read gesture settings", error);
        }
    }

    private final class FullScreenReply {
        private static final int HIDE_BANNER_MESSAGE = 0;
        private final Field bannerView, touchHandler, currentEntry, bannerHelper, replyLayout, replyEditor, bannerText;
        private final Field nativeIntent, nativeInput, buttons, wrapperTarget, clickOwner, clickContext, clickInput;
        private final Field displayHandler, dialogNotification;
        private final Method closeRemoteInputs, hideBanner;
        private final Class<?> wrapperType, clickType;
        private FullScreenAction action;
        private NotificationGesture gesture;
        private ViewGroup touchedContainer;

        FullScreenReply(ClassLoader loader, Class<?> entryType) throws ReflectiveOperationException {
            String prefix = "com.oplus.systemui.notification.interruption.fullscreenbanner.";
            Class<?> helper = loader.loadClass(prefix + "FullScreenBannerHelper");
            Class<?> banner = loader.loadClass(prefix + "view.FullScreenBanner");
            Class<?> container = loader.loadClass(prefix + "view.FullScreenBannerContainer");
            Class<?> touch = loader.loadClass(prefix + "view.FullScreenBannerTouchHandler");
            Class<?> builder = loader.loadClass(banner.getName() + "$Builder");
            wrapperType = loader.loadClass(banner.getName() + "$2");
            clickType = loader.loadClass(helper.getName() + "$$ExternalSyntheticLambda2");
            bannerView = field(container, "mFullScreenBanner");
            touchHandler = field(container, "mTouchHandler");
            currentEntry = field(touch, "mEntry");
            bannerHelper = field(container, "mHelper");
            replyLayout = field(banner, "mReplyLayout");
            replyEditor = field(banner, "mReplyEditText");
            bannerText = field(banner, "mTvContent");
            nativeIntent = field(helper, "mPendingIntent");
            nativeInput = field(helper, "mRemoteInput");
            closeRemoteInputs = method(helper, "closeRemoteInputs");
            hideBanner = method(helper, "hideBanner", Runnable[].class);
            displayHandler = field(helper, "mHandler");
            dialogNotification = field(helper, "mHasDialogNotification");
            buttons = field(builder, "mBtnMap");
            wrapperTarget = field(wrapperType, "this$0");
            clickOwner = field(clickType, "f$0");
            clickContext = field(clickType, "f$1");
            clickInput = field(clickType, "f$2");
            Method build = method(helper, "buildNotification", Context.class, entryType);
            Method dispatch = method(container, "dispatchTouchEvent", MotionEvent.class);
            hook(build).intercept(chain -> {
                Object result = chain.proceed();
                guarded(() -> bind(chain.getThisObject(), chain.getArg(1), result));
                return result;
            });
            hook(dispatch).intercept(this::dispatch);
        }

        private StatusBarNotification notification(Object entry) throws ReflectiveOperationException {
            return entry == null ? null : (StatusBarNotification) getSbn.invoke(entry);
        }

        private void bind(Object helper, Object entry, Object builder) throws ReflectiveOperationException {
            action = null;
            StatusBarNotification sbn = notification(entry);
            if (sbn == null || builder == null || !(buttons.get(builder) instanceof Map<?, ?> map)) return;
            if (!(nativeIntent.get(helper) instanceof PendingIntent intent) || intent.isImmutable()
                    || !(nativeInput.get(helper) instanceof RemoteInput input) || !isTextReply(input)) return;
            Notification.Action[] actions = sbn.getNotification().actions;
            if (actions == null) return;
            for (Notification.Action item : actions) {
                if (item == null || !intent.equals(item.actionIntent) || item.getRemoteInputs() == null) continue;
                for (RemoteInput candidate : item.getRemoteInputs()) {
                    if (isTextReply(candidate) && input.getResultKey().equals(candidate.getResultKey())) {
                        bindButton(helper, sbn.getKey(), intent, input, map);
                        return;
                    }
                }
            }
        }

        private void bindButton(Object helper, String key, PendingIntent intent, RemoteInput input, Map<?, ?> map)
                throws ReflectiveOperationException {
            for (Object value : map.values()) {
                if (!wrapperType.isInstance(value)) continue;
                Object listener = wrapperTarget.get(value);
                // The same generated class handles ordinary actions. Only its editor callback
                // captures a Context and a RemoteInput instead of an action and an entry.
                if (clickType.isInstance(listener) && clickOwner.get(listener) == helper
                        && clickContext.get(listener) instanceof Context && clickInput.get(listener) instanceof RemoteInput
                        && value instanceof View.OnClickListener openEditor) {
                    action = new FullScreenAction(key, intent, input, openEditor);
                    return;
                }
            }
        }

        private record FullScreenAction(String key, PendingIntent intent, RemoteInput input,
                                        View.OnClickListener openEditor) {}

        private boolean matches(ViewGroup container, View banner, String key) throws ReflectiveOperationException {
            if (action == null || !action.key().equals(key) || !banner.isShown() || !unlocked(banner)
                    || bannerView.get(container) != banner) return false;
            Object helper = bannerHelper.get(container);
            Object touch = touchHandler.get(container);
            StatusBarNotification shown = touch == null ? null : notification(currentEntry.get(touch));
            return helper != null && shown != null && key.equals(shown.getKey())
                    && action.intent().equals(nativeIntent.get(helper))
                    && nativeInput.get(helper) instanceof RemoteInput input
                    && action.input().getResultKey().equals(input.getResultKey());
        }

        private Object dispatch(Chain chain) throws Throwable {
            ViewGroup container = (ViewGroup) chain.getThisObject();
            MotionEvent event = (MotionEvent) chain.getArg(0);
            if (handleOpenReplyPull(container, event, (window, down) -> {
                if (!(bannerView.get(window) instanceof View banner) || !touches(banner, down)
                        || !(replyLayout.get(banner) instanceof View holder)) return null;
                if (holder.isShown() && touches(holder, down)) return null;
                BannerSwipeUp swipe = fullScreenSwipeUp(container, banner, holder);
                return holder.isShown() ? new OpenReplyDrag(window, banner, swipe, true)
                        : (swipe == null ? null : new OpenReplyDrag(null, null, swipe, false));
            })) return true;
            int type = event.getActionMasked();
            if (type == MotionEvent.ACTION_DOWN) {
                clear();
                guarded(() -> start(container, event));
                return chain.proceed();
            }
            NotificationGesture current = gesture;
            if (current == null || touchedContainer != container) return chain.proceed();
            if (type == MotionEvent.ACTION_MOVE && current.swipeUp == null && current.followsSwipeUp(event)) {
                guarded(() -> {
                    if (replyLayout.get(current.row) instanceof View holder && holder.isShown()) {
                        current.swipeUp = fullScreenSwipeUp(container, current.row, holder);
                    }
                });
                if (current.swipeUp != null && !current.cancelledNativeTouch) {
                    current.cancelledNativeTouch = true;
                    cancelNativeTouch(chain, event);
                }
            }
            if (current.handleSwipeUp(event)) {
                if (type == MotionEvent.ACTION_POINTER_DOWN) current.pullCancelled = true;
                if (type == MotionEvent.ACTION_UP || type == MotionEvent.ACTION_CANCEL) clear();
                return true;
            }
            if (type == MotionEvent.ACTION_MOVE) {
                if (current.movedPastHold(event)) main.removeCallbacks(current.hold);
                if (current.cancelledNativeTouch && current.shouldStartBannerPreview(event))
                    current.startPull(container);
                if (current.isOtherGesture(event)) {
                    if (current.handled || current.cancelledNativeTouch) {
                        current.updatePull(event);
                    } else {
                        clear();
                        return chain.proceed();
                    }
                } else {
                    if (!current.cancelledNativeTouch && (current.handled
                            || (current.pullEnabled && current.isDownwardPull(event, current.slop)))) {
                        current.cancelledNativeTouch = true;
                        cancelNativeTouch(chain, event);
                        if (current.shouldStartBannerPreview(event)) current.startPull(container);
                    }
                    if (!current.handled) current.updatePull(event);
                    if (current.reachesBannerThreshold(event)) commitPull(container, current);
                }
                return current.cancelledNativeTouch ? true : chain.proceed();
            }
            if (type == MotionEvent.ACTION_UP || type == MotionEvent.ACTION_CANCEL
                    || type == MotionEvent.ACTION_POINTER_DOWN) {
                boolean owned = current.handled || current.cancelledNativeTouch;
                if (type == MotionEvent.ACTION_POINTER_DOWN && owned) {
                    current.pullCancelled = true;
                    current.endMotion();
                    return true;
                }
                try {
                    if (type == MotionEvent.ACTION_UP && current.reachesBannerThreshold(event))
                        commitPull(container, current);
                    if (current.cancelledNativeTouch) return true;
                    if (current.handled) return cancelNativeTouch(chain, event);
                    return chain.proceed();
                } finally {
                    clear();
                }
            }
            return current.cancelledNativeTouch || current.handled ? true : chain.proceed();
        }

        private void closeReply(ViewGroup container, View banner, String key) throws ReflectiveOperationException {
            if (!matches(container, banner, key)
                    || !(replyLayout.get(banner) instanceof View holder) || !holder.isShown()) return;
            ++requestId;
            ReplyReveal.cancel(holder);
            closeRemoteInputs.invoke(bannerHelper.get(container));
        }

        private BannerSwipeUp fullScreenSwipeUp(ViewGroup container, View banner, View holder)
                throws ReflectiveOperationException {
            if (action == null || bannerSwipes.containsKey(banner) || !matches(container, banner, action.key())) return null;
            String key = action.key();
            boolean expanded = holder.isShown();
            if (!expanded && !key.equals(compactSwipeKeys.get(banner))) return null;
            int height = banner.getHeight();
            int compact = expanded ? compactHeight(banner, key, height - holder.getHeight()) : height;
            if (banner.getLayoutParams() == null || container.getLayoutParams() == null) return null;
            return newBannerSwipe(banner, container, expanded ? holder : null, null, null, null, height, compact, expanded,
                    new FullScreenBannerHeight(banner, container), () -> {
                try {
                    if (!banner.isShown() || bannerView.get(container) != banner) return false;
                    Object touch = touchHandler.get(container);
                    StatusBarNotification shown = touch == null ? null : notification(currentEntry.get(touch));
                    return shown != null && key.equals(shown.getKey());
                } catch (ReflectiveOperationException | RuntimeException error) {
                    warn(error);
                    return false;
                }
            }, () -> pauseTimeout(container, banner, holder), () -> {
                closeReply(container, banner, key);
                compactSwipeKeys.put(banner, key);
            },
                    finished -> {
                        compactSwipeKeys.remove(banner);
                        hideBanner.invoke(bannerHelper.get(container), (Object) new Runnable[]{ finished });
                    },
                    backdrop -> backdrop.keepForEditor(holder, holder::isShown));
        }

        private void start(ViewGroup container, MotionEvent event) throws ReflectiveOperationException {
            if (action == null || !(bannerView.get(container) instanceof View banner)
                    || !matches(container, banner, action.key())) return;
            if (!touches(banner, event)) return;
            guarded(() -> resetTimeout(container));
            if (!(replyLayout.get(banner) instanceof View holder) || holder.isShown()) return;
            GestureConfig settings = config;
            NotificationGesture started = new NotificationGesture(banner, action.key(), event,
                    settings, settings.bannerDp());
            started.hold = () -> guarded(() -> {
                if (gesture == started && !started.handled) {
                    started.handled = open(container, started);
                    if (started.handled) new PullDeformation(container, banner).pulse();
                }
            });
            gesture = started;
            touchedContainer = container;
            postHold(started);
        }

        private void commitPull(ViewGroup container, NotificationGesture current) {
            guarded(() -> {
                if (!matches(container, current.row, current.key)) return;
                guarded(() -> resetTimeout(container));
                current.handled = open(container, current);
                if (current.handled) {
                    current.endMotion();
                }
            });
        }

        private void resetTimeout(ViewGroup container) throws ReflectiveOperationException {
            Object helper = bannerHelper.get(container);
            if (helper == null || !(displayHandler.get(helper) instanceof Handler handler)
                    || !handler.hasMessages(HIDE_BANNER_MESSAGE)) return;
            restartTimeout(helper, handler);
        }

        private CheckedAction pauseTimeout(ViewGroup container, View banner, View holder)
                throws ReflectiveOperationException {
            Object helper = bannerHelper.get(container);
            if (helper == null || !(displayHandler.get(helper) instanceof Handler handler)) return () -> {};
            handler.removeMessages(HIDE_BANNER_MESSAGE);
            return () -> {
                if (bannerView.get(container) == banner && banner.isShown() && !holder.isShown())
                    restartTimeout(helper, handler);
            };
        }

        private void restartTimeout(Object helper, Handler handler) throws ReflectiveOperationException {
            // Use the same durations as FullScreenBannerHelper.showBanner on this firmware.
            handler.removeMessages(HIDE_BANNER_MESSAGE);
            handler.sendEmptyMessageDelayed(HIDE_BANNER_MESSAGE, dialogNotification.getBoolean(helper) ? 10_000 : 5_000);
        }

        private boolean open(ViewGroup container, NotificationGesture current) throws ReflectiveOperationException {
            if (!matches(container, current.row, current.key)
                    || !(replyLayout.get(current.row) instanceof View holder) || holder.isShown()) return false;
            if (current.row.getHeight() > 0)
                compactBannerSizes.put(current.row, new BannerSize(current.key, current.row.getHeight()));
            action.openEditor().onClick(current.row);
            if (!(replyEditor.get(current.row) instanceof View editor)
                    || holder.getVisibility() != View.VISIBLE) return false;
            ReplyBackdrop backdrop = current.backdrop != null ? current.backdrop : ReplyBackdrop.forView(holder);
            if (backdrop != null) backdrop.keepForEditor(holder, holder::isShown);
            if (bannerText.get(current.row) instanceof View history) ReplyReveal.show(history, false);
            ReplyReveal.show(holder, true);
            int request = ++requestId;
            current.row.postOnAnimation(() -> confirmVibration(current.row, editor, request, 4, true));
            log(Log.INFO, TAG, "Opened reply in the fullscreen banner", null);
            return true;
        }

        private void clear() {
            if (gesture != null) {
                main.removeCallbacks(gesture.hold);
                gesture.endMotion();
            }
            gesture = null;
            touchedContainer = null;
        }
    }

    private void installBanner(ClassLoader loader, String name) {
        try {
            Class<?> klass = loader.loadClass(name);
            Field picked = field(klass, "mPickedChild");
            Field manager = field(klass, "mHeadsUpManager");
            Method intercept = method(klass, "onInterceptTouchEvent", MotionEvent.class);
            Method touch = method(klass, "onTouchEvent", MotionEvent.class);
            hook(intercept).intercept(chain -> handleBanner(chain, picked, manager, true));
            hook(touch).intercept(chain -> handleBanner(chain, picked, manager, false));
        } catch (ReflectiveOperationException | RuntimeException error) {
            log(Log.WARN, TAG, "Banner hook unavailable: " + name, error);
        }
    }

    private Object handleBanner(Chain chain, Field picked, Field manager, boolean intercept)
            throws Throwable {
        Object helper = chain.getThisObject();
        MotionEvent event = (MotionEvent) chain.getArg(0);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN && intercept) {
            clearBanner(helper);
            Object result = chain.proceed();
            guarded(() -> {
                Object selected = picked.get(helper);
                if (!rowType.isInstance(selected)) return;
                Candidate target = candidate((View) selected);
                if (target == null) return;
                resetBannerTimeout(helper, manager, target.notification().getKey());
                guarded(() -> rememberCompactActions(target));
                if (remoteInputManager == null) return;
                GestureConfig settings = config;
                if (activeEditor(target.row()) != null ||
                        (!target.notification().getKey().equals(compactSwipeKeys.get(target.row()))
                                && (boolean) isInputActive.invoke(remoteInputManager))) return;
                NotificationGesture gesture = new NotificationGesture(target, event, settings,
                        settings.bannerDp());
                gesture.hold = () -> {
                    if (bannerGestures.get(helper) == gesture && !gesture.handled) {
                        gesture.handled = tryBannerReply(gesture.row, gesture.key);
                        if (gesture.handled) new PullDeformation(gesture.row, gesture.row).pulse();
                    }
                };
                bannerGestures.put(helper, gesture);
                postHold(gesture);
            });
            return result;
        }

        NotificationGesture gesture = bannerGestures.get(helper);
        if (gesture == null) return chain.proceed();
        if (action == MotionEvent.ACTION_MOVE && gesture.swipeUp == null && gesture.followsSwipeUp(event)) {
            guarded(() -> {
                gesture.swipeUp = rowSwipeUp(gesture.row, false);
            });
            if (gesture.swipeUp != null && !gesture.cancelledNativeTouch) {
                gesture.cancelledNativeTouch = true;
                cancelNativeTouch(chain, event);
            }
        }
        if (gesture.handleSwipeUp(event)) {
            if (action == MotionEvent.ACTION_POINTER_DOWN) gesture.pullCancelled = true;
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) clearBanner(helper);
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (gesture.movedPastHold(event)) main.removeCallbacks(gesture.hold);
            if (!gesture.handled && !gesture.cancelledNativeTouch && gesture.isOtherGesture(event)) {
                clearBanner(helper);
                return chain.proceed();
            }
            if (!gesture.handled && !gesture.cancelledNativeTouch && gesture.pullEnabled
                    && gesture.isDownwardPull(event, gesture.slop)) {
                gesture.cancelledNativeTouch = true;
                cancelNativeTouch(chain, event);
            }
            if (gesture.cancelledNativeTouch && gesture.shouldStartBannerPreview(event)) {
                guarded(() -> gesture.bannerPreview = startBannerPreview(gesture));
                if (gesture.bannerPreview == null) gesture.startPull(gesture.row);
                else {
                    gesture.startBackdrop();
                    gesture.bannerPreview.backdrop = gesture.backdrop;
                }
            }
            if (!gesture.handled) gesture.updatePull(event);
            if (gesture.reachesBannerThreshold(event)) commitBannerPull(helper, manager, gesture);
        }
        boolean owned = gesture.handled || gesture.cancelledNativeTouch;
        if (action == MotionEvent.ACTION_POINTER_DOWN && owned) {
            gesture.pullCancelled = true;
            gesture.endMotion();
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                || action == MotionEvent.ACTION_POINTER_DOWN) {
            if (action == MotionEvent.ACTION_UP && gesture.reachesBannerThreshold(event))
                commitBannerPull(helper, manager, gesture);
            clearBanner(helper);
        }
        return owned ? Boolean.TRUE : chain.proceed();
    }

    private void commitBannerPull(Object helper, Field manager, NotificationGesture gesture) {
        gesture.handled = tryBannerReply(gesture.row, gesture.key, gesture);
        if (gesture.handled) {
            resetBannerTimeout(helper, manager, gesture.key);
            gesture.endMotion();
        }
    }

    private void resetBannerTimeout(Object helper, Field manager, String key) {
        if (headsUpTimeout != null) guarded(() -> headsUpTimeout.reset(manager.get(helper), key));
    }

    private static final class HeadsUpTimeout {
        private final Class<?> managerType, paneManagerType;
        private final Field origin, paused;
        private final Method getEntry, updateEntry, setPaused;

        HeadsUpTimeout(ClassLoader loader) throws ReflectiveOperationException {
            String name = SYSTEM_UI + ".statusbar.notification.headsup.HeadsUpManagerImpl";
            managerType = loader.loadClass(name);
            getEntry = method(managerType, "getHeadsUpEntry", String.class);
            Class<?> entryType = loader.loadClass(name + "$HeadsUpEntry");
            updateEntry = method(entryType, "updateEntry", String.class, boolean.class);
            setPaused = method(entryType, "setPaused", boolean.class);
            paused = field(entryType, "mPaused");
            paneManagerType = loader.loadClass("com.oplus.systemui.notification.headsup.paneview.OplusPaneViewHeadsUpManager");
            origin = field(paneManagerType, "origin");
        }

        void reset(Object manager, String key) throws ReflectiveOperationException {
            Object entry = find(manager, key);
            if (entry != null) updateEntry.invoke(entry, "ShadeReply touch", true);
        }

        CheckedAction pause(Object manager, String key) throws ReflectiveOperationException {
            Object entry = find(manager, key);
            if (entry == null) return () -> {};
            boolean pausedHere = !paused.getBoolean(entry);
            if (pausedHere) setPaused.invoke(entry, true);
            return () -> {
                if (find(manager, key) != entry) return;
                // Refresh the native post time while removal is paused, then schedule the new deadline.
                try {
                    updateEntry.invoke(entry, "ShadeReply swipe settled", true);
                } finally {
                    if (pausedHere) setPaused.invoke(entry, false);
                }
            };
        }

        private Object find(Object manager, String key) throws ReflectiveOperationException {
            if (paneManagerType.isInstance(manager)) manager = origin.get(manager);
            return managerType.isInstance(manager) ? getEntry.invoke(manager, key) : null;
        }

    }

    private BannerHeightPreview startBannerPreview(NotificationGesture gesture)
            throws ReflectiveOperationException {
        if (swipingToExpand == null || expandedWhenPinned == null || !ValueAnimator.areAnimatorsEnabled())
            return null;
        View row = gesture.row;
        BannerHeightPreview previous = bannerPreviews.get(row);
        if (previous != null) previous.close(false);
        Candidate target = bannerCandidate(row, gesture.key);
        if (target == null || !(boolean) isPinned.invoke(row) || expandedWhenPinned.getBoolean(row)
                || swipingToExpand.getBoolean(row) || (boolean) isGroupSummary.invoke(row)) return null;
        Object layout = getLayout.invoke(row);
        if (layout == null) return null;
        View expandedChild = (View) getChild.invoke(layout);
        View editor = (View) getEditor.invoke(layout);
        if (findBannerControl(target, expandedChild, editor) == null) return null;
        int from = (int) getActualHeight.invoke(row);
        int to = (int) getMaxContentHeight.invoke(row);
        if (from <= 0 || to <= from) return null;
        View compact = (View) getHeadsUpChild.invoke(layout);
        View actions = compact == null ? null : compact.findViewById(ACTIONS_CONTAINER_LAYOUT);
        View button = compactReplyButton(target, actions);
        Rect bounds = compactReplyBounds(row, button, actions, from);
        View expandedActions = expandedChild.findViewById(ACTIONS_CONTAINER_LAYOUT);
        BannerReplyMotion.PullOrigin replyOrigin = BannerReplyMotion.pullOrigin(row, button, editor,
                expandedActions == actions ? null : expandedActions, bounds);
        BannerTemplateTransition.ActionStrip strip = replyOrigin == null ? null
                : compactActionsForTransition(row, gesture.key, actions, from);
        BannerTemplateTransition templateTransition = BannerTemplateTransition.create(row, compact, expandedChild,
                button, bounds, target.notification().getNotification(), from, to, actions, strip, replyOrigin != null);
        BannerHeightPreview preview = new BannerHeightPreview(row, from, to, templateTransition,
                templateTransition == null ? null : replyOrigin);
        bannerPreviews.put(row, preview);
        row.addOnAttachStateChangeListener(preview);
        try {
            setSwipingToExpand.invoke(row, true);
            return preview;
        } catch (ReflectiveOperationException | RuntimeException error) {
            preview.close(false);
            throw error;
        }
    }

    private final class BannerHeightPreview implements View.OnAttachStateChangeListener {
        private final View row;
        private final int initial, expanded;
        private final BannerTemplateTransition templateTransition;
        private final BannerReplyMotion.PullOrigin replyOrigin;
        private final Choreographer choreographer = Choreographer.getInstance();
        private final Choreographer.FrameCallback followFrame = this::onFrame;
        private int height, openingHeight;
        private float displayedHeight;
        private float targetHeight;
        private long lastFrameNanos;
        private boolean frameQueued;
        private ValueAnimator settle;
        private ReplyBackdrop backdrop;
        private boolean closed, opening, writingHeight;

        BannerHeightPreview(View row, int initial, int expanded, BannerTemplateTransition templateTransition,
                BannerReplyMotion.PullOrigin replyOrigin) {
            this.row = row;
            this.height = this.initial = initial;
            this.displayedHeight = this.targetHeight = initial;
            this.expanded = expanded;
            this.templateTransition = templateTransition;
            this.replyOrigin = replyOrigin;
        }

        void follow(float distance, float start, float threshold) {
            if (closed || settle != null) return;
            // Start at zero velocity, then approach the expanded height as the finger moves.
            float preview = 0.90f * bannerProgress(distance, start, threshold);
            targetHeight = initial + (expanded - initial) * preview;
            if (displayedHeight != targetHeight) queueFrame();
        }

        private void queueFrame() {
            if (frameQueued) return;
            frameQueued = true;
            choreographer.postFrameCallback(followFrame);
        }

        private void onFrame(long timeNanos) {
            frameQueued = false;
            if (closed || settle != null) return;
            float elapsedMs = lastFrameNanos == 0 ? 8f : Math.min(24f, (timeNanos - lastFrameNanos) / 1_000_000f);
            lastFrameNanos = timeNanos;
            float blend = (float) (1d - Math.exp(-elapsedMs / 18f));
            displayedHeight += (targetHeight - displayedHeight) * blend;
            if (Math.abs(targetHeight - displayedHeight) < 0.5f) displayedHeight = targetHeight;
            renderHeight(Math.round(displayedHeight));
            if (!closed && displayedHeight != targetHeight) queueFrame();
            else lastFrameNanos = 0;
        }

        private void stopFollowing() {
            if (frameQueued) choreographer.removeFrameCallback(followFrame);
            frameQueued = false;
            lastFrameNanos = 0;
        }

        private void resize(int nextHeight) {
            if (height == nextHeight || closed) return;
            try {
                writingHeight = true;
                setActualHeight.invoke(row, nextHeight, true);
                height = nextHeight;
            } catch (ReflectiveOperationException | RuntimeException error) {
                warn(error);
                close(opening);
            } finally {
                writingHeight = false;
            }
        }

        float progressAt(int currentHeight) {
            return (float) (currentHeight - initial) / (expanded - initial);
        }

        private void renderHeight(int nextHeight) {
            resize(nextHeight);
            if (closed) return;
            float progress = progressAt(nextHeight);
            if (templateTransition != null) templateTransition.render(progress, nextHeight);
            if (replyOrigin != null) {
                float openingProgress = opening
                        ? (float) (nextHeight - openingHeight) / Math.max(1, expanded - openingHeight) : 0f;
                replyOrigin.follow(progress, nextHeight - initial, openingProgress);
            }
            if (backdrop != null) backdrop.drag(progress);
        }

        void finish(boolean committed) {
            if (closed || settle != null) return;
            stopFollowing();
            opening = committed;
            openingHeight = height;
            int end = committed ? expanded : initial;
            if (height == end || !row.isShown()) {
                close(committed);
                return;
            }
            // A fast pull can cross the threshold before the first follow frame.
            renderHeight(height);
            if (closed) return;
            settle = ValueAnimator.ofInt(height, end);
            settle.setDuration(committed ? 160 : 200);
            settle.setInterpolator(new PathInterpolator(0.20f, 0.68f, 0.24f, 1f));
            settle.addUpdateListener(animation -> renderHeight((int) animation.getAnimatedValue()));
            settle.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) { close(committed); }
            });
            settle.start();
        }

        void close(boolean committed) {
            if (closed) return;
            closed = true;
            stopFollowing();
            row.removeOnAttachStateChangeListener(this);
            if (bannerPreviews.get(row) == this) bannerPreviews.remove(row);
            if (settle != null) {
                settle.removeAllUpdateListeners();
                settle.removeAllListeners();
                settle.cancel();
            }
            if (!committed && backdrop != null) backdrop.collapse();
            try {
                if (!committed && (int) getActualHeight.invoke(row) == height && height != initial)
                    setActualHeight.invoke(row, initial, row.isAttachedToWindow());
            } catch (ReflectiveOperationException | RuntimeException error) {
                warn(error);
            } finally {
                guarded(() -> setSwipingToExpand.invoke(row, false));
            }
            if (committed && row.isAttachedToWindow())
                guarded(() -> notifyBannerHeight(row, "QuickReply.expandPinnedBanner", true));
            if (templateTransition != null) templateTransition.finish();
            if (replyOrigin != null) {
                if (committed) replyOrigin.waitForEditor();
                else replyOrigin.finish();
            }
        }

        @Override public void onViewAttachedToWindow(View view) {}
        @Override public void onViewDetachedFromWindow(View view) { close(false); }
    }

    private void clearBanner(Object helper) {
        NotificationGesture old = bannerGestures.remove(helper);
        if (old != null) {
            main.removeCallbacks(old.hold);
            old.endMotion();
        }
    }

    private void clearShadeGesture() {
        if (shadeGesture != null) {
            main.removeCallbacks(shadeGesture.hold);
            shadeGesture.endMotion();
        }
        shadeGesture = null;
    }

    private void postHold(NotificationGesture gesture) {
        if (gesture.holdMs == 0) return;
        main.postAtTime(gesture.hold,
                Math.max(SystemClock.uptimeMillis(), gesture.downTime + gesture.holdMs));
    }

    private Object handleShadeTouch(Chain chain, Method childAtTouch) throws Throwable {
        ViewGroup list = (ViewGroup) chain.getThisObject();
        MotionEvent event = (MotionEvent) chain.getArg(0);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            clearShadeGesture();
            guarded(() -> {
                if (!shadeReady(list) || !unlocked(list) || remoteInputManager == null) return;
                Object selected = childAtTouch.invoke(list, event.getRawX(), event.getRawY());
                if (!rowType.isInstance(selected)) return;
                View row = (View) selected;
                Rect bounds = new Rect();
                int[] rootOrigin = new int[2];
                row.getRootView().getLocationOnScreen(rootOrigin);
                if (!row.getGlobalVisibleRect(bounds)
                        || !bounds.contains((int) event.getRawX() - rootOrigin[0],
                                           (int) event.getRawY() - rootOrigin[1])) return;
                Candidate target = candidate(row);
                if (target == null) return;
                GestureConfig settings = config;
                if (!settings.hold() && !settings.pull()) return;
                if (activeEditor(row) != null) return;
                NotificationGesture gesture = new NotificationGesture(target, event, settings,
                        settings.shadeDp());
                gesture.hold = () -> {
                    if (shadeGesture == gesture && !gesture.handled)
                        gesture.handled = tryShadeReply(gesture.row, gesture.key);
                };
                shadeGesture = gesture;
                postHold(gesture);
            });
            return chain.proceed();
        }

        NotificationGesture gesture = shadeGesture;
        if (gesture == null) return chain.proceed();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            clearShadeGesture();
            if (!gesture.handled) return chain.proceed();
            if (gesture.cancelledNativeTouch) return true;
            return cancelNativeTouch(chain, event);
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN
                || (action == MotionEvent.ACTION_MOVE && gesture.isOtherGesture(event))) {
            if (gesture.handled) {
                gesture.endMotion();
                return true;
            }
            clearShadeGesture();
            return chain.proceed();
        }
        if (action == MotionEvent.ACTION_MOVE && gesture.movedPastHold(event))
            main.removeCallbacks(gesture.hold);
        if (gesture.handled) return true;
        if (action != MotionEvent.ACTION_MOVE || !gesture.confirmsPull(event)
                || !tryShadeReply(gesture.row, gesture.key)) return chain.proceed();
        gesture.handled = true;
        gesture.cancelledNativeTouch = true;
        return cancelNativeTouch(chain, event);
    }

    private boolean cancelNativeTouch(Chain chain, MotionEvent event) throws Throwable {
        MotionEvent cancel = MotionEvent.obtain(event);
        try {
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            chain.proceed(new Object[] {cancel});
        } finally {
            cancel.recycle();
        }
        return true;
    }

    private boolean tryShadeReply(View row, String expectedKey) {
        try {
            Candidate target = candidate(row);
            ViewGroup list = stack();
            if (target == null || remoteInputManager == null || list == null
                    || !shadeReady(list) || !isDescendantOf(row, list)) return false;
            String key = target.notification().getKey();
            if (expectedKey != null && !expectedKey.equals(key)) return false;
            tryOpen(key, ++requestId, 0);
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            warn(error);
            return false;
        }
    }

    private Object switchShadeReply(Chain chain) throws Throwable {
        if (!(chain.getArg(0) instanceof View anchor) || !(chain.getArg(2) instanceof RemoteInput input)
                || !isTextReply(input) || Looper.myLooper() != Looper.getMainLooper()) return chain.proceed();
        ShadeReplySwitch change;
        try {
            change = prepareShadeReplySwitch(anchor, chain.getThisObject());
        } catch (ReflectiveOperationException | RuntimeException error) {
            warn(error);
            return chain.proceed();
        }
        Object opened = chain.proceed();
        if (change != null && Boolean.TRUE.equals(opened)) {
            shadeReplyRow = new WeakReference<>(change.target());
            shadeReplyKey = change.key();
            if (change.previous() != null) guarded(() -> {
                closeEditor(visibleEditor(change.previous()), false);
                setUserExpanded.invoke(change.previous(), false);
            });
        }
        return opened;
    }

    private ShadeReplySwitch prepareShadeReplySwitch(View anchor, Object manager)
            throws ReflectiveOperationException {
        ViewGroup list = stack();
        if (list == null || !shadeReady(list) || !unlocked(list)) return null;
        View row = anchor;
        while (!rowType.isInstance(row)) {
            if (!(row.getParent() instanceof View parent)) return null;
            row = parent;
        }
        if (!isDescendantOf(row, list) || privateRow.getBoolean(row)) return null;
        String key = notificationKey(row);
        if (key == null) return null;

        View previous = shadeReplyRow.get();
        if (previous == row || previous == null || !isDescendantOf(previous, list)
                || !java.util.Objects.equals(shadeReplyKey, notificationKey(previous))) {
            previous = Boolean.TRUE.equals(isInputActive.invoke(manager)) ? findOtherShadeEditor(list, row) : null;
        }
        return new ShadeReplySwitch(row, key, previous);
    }

    private View findOtherShadeEditor(View view, View target) throws ReflectiveOperationException {
        if (!view.isShown() || view == target) return null;
        if (rowType.isInstance(view) && activeEditor(view) != null) return view;
        if (view instanceof ViewGroup group) {
            for (int index = 0; index < group.getChildCount(); index++) {
                View active = findOtherShadeEditor(group.getChildAt(index), target);
                if (active != null) return active;
            }
        }
        return null;
    }

    private String notificationKey(View row) throws ReflectiveOperationException {
        Object entry = getEntry.invoke(row);
        return entry != null && getSbn.invoke(entry) instanceof StatusBarNotification sbn ? sbn.getKey() : null;
    }

    private boolean tryBannerReply(View row, String expectedKey) {
        return tryBannerReply(row, expectedKey, null);
    }

    private boolean tryBannerReply(View row, String expectedKey, NotificationGesture pull) {
        try {
            Candidate target = bannerCandidate(row, expectedKey);
            if (target == null) return false;
            Object layout = getLayout.invoke(row);
            if (layout == null) return false;
            View expanded = (View) getChild.invoke(layout);
            View expandedEditor = (View) getEditor.invoke(layout);
            if (expandedWhenPinned != null && (boolean) isPinned.invoke(row)
                    && findBannerControl(target, expanded, expandedEditor) != null) {
                if (expandedEditor.isShown() && (boolean) isEditorActive.invoke(expandedEditor)) return true;
                BannerReplyAttempt reply = new BannerReplyAttempt(target);
                if (!expandedWhenPinned.getBoolean(row)) {
                    BannerHeightPreview preview = pull == null ? null : pull.bannerPreview;
                    boolean historyPreviewed = preview != null && !preview.closed;
                    int originalHeight = historyPreviewed ? preview.initial
                            : getActualHeight == null ? 0 : (int) getActualHeight.invoke(row);
                    if (originalHeight > 0)
                        compactBannerSizes.put(row, new BannerSize(expectedKey, originalHeight));
                    if (!historyPreviewed) guarded(() -> rememberCompactActions(target));
                    reply.replyButton = compactReplyBounds(target, (View) getHeadsUpChild.invoke(layout), originalHeight);
                    int expandedHeight = getMaxContentHeight == null ? row.getHeight()
                            : (int) getMaxContentHeight.invoke(row);
                    BannerTemplateTransition templateTransition = historyPreviewed ? null
                            : bannerTemplateTransition(target, layout, originalHeight, expandedHeight);
                    if (templateTransition != null) templateTransition.render(0f, originalHeight);
                    reply.pullOrigin = historyPreviewed ? preview.replyOrigin : null;
                    if (reply.pullOrigin == null)
                        reply.releaseOrigin = BannerReplyMotion.holdOrigin(row, reply.replyButton);
                    try {
                        setBannerExpanded(row, true, historyPreviewed ? preview : null);
                    } catch (ReflectiveOperationException | RuntimeException failure) {
                        if (templateTransition != null) templateTransition.finish();
                        reply.clearOrigin();
                        throw failure;
                    }
                    reply.expandedByGesture = true;
                    if (templateTransition != null) templateTransition.finishOpening(expandedHeight);
                    if (!historyPreviewed) revealHistory(expanded, expandedEditor);
                }
                reply.trackPull(pull);
                if (reply.backdrop != null && !bannerPreviews.containsKey(row)) reply.backdrop.expand();
                awaitExpandedBanner(reply, 0);
                return true;
            }
            return openCompactBanner(target, null, pull);
        } catch (ReflectiveOperationException | RuntimeException error) {
            warn(error);
            return false;
        }
    }

    private final class BannerReplyAttempt {
        final View row;
        final String key;
        final int request = ++requestId;
        boolean expandedByGesture;
        Rect replyButton;
        Runnable releaseOrigin;
        BannerReplyMotion.PullOrigin pullOrigin;
        ReplyBackdrop backdrop;

        BannerReplyAttempt(Candidate target) {
            row = target.row();
            key = target.notification().getKey();
        }

        void trackPull(NotificationGesture pull) {
            if (pull == null) return;
            backdrop = pull.backdrop;
        }

        void revealEditor(View editor) {
            Rect start = pullOrigin == null ? null : pullOrigin.currentBounds();
            ReplyReveal.showEditor(editor, row, start == null ? replyButton : start,
                    start == null ? releaseOrigin : pullOrigin::finish);
            pullOrigin = null;
            releaseOrigin = null;
        }

        void clearOrigin() {
            if (pullOrigin != null) pullOrigin.finish();
            if (releaseOrigin != null) {
                row.removeCallbacks(releaseOrigin);
                releaseOrigin.run();
                releaseOrigin = null;
            }
        }

        void watchEditor(View editor) {
            if (backdrop == null) backdrop = ReplyBackdrop.forView(editor);
            watchEditorBackdrop(backdrop, editor);
        }

        void restoreHeight() throws ReflectiveOperationException {
            if (!expandedByGesture) return;
            if ((boolean) isPinned.invoke(row) && expandedWhenPinned.getBoolean(row))
                setBannerExpanded(row, false);
            expandedByGesture = false;
        }

    }

    private Candidate bannerCandidate(View row, String key) throws ReflectiveOperationException {
        Candidate target = candidate(row);
        if (target == null || remoteInputManager == null || !(boolean) isHeadsUp.invoke(row)
                || (key != null && !key.equals(target.notification().getKey()))) return null;
        return target;
    }

    private void setBannerExpanded(View row, boolean expanded) throws ReflectiveOperationException {
        setBannerExpanded(row, expanded, null);
    }

    private void setBannerExpanded(View row, boolean expanded, BannerHeightPreview preview)
            throws ReflectiveOperationException {
        boolean previous = expandedWhenPinned.getBoolean(row);
        expandedWhenPinned.setBoolean(row, expanded);
        try {
            if (preview != null) {
                preview.finish(true);
            } else {
                // Use the same height change that ColorOS uses for the pinned expand button.
                notifyBannerHeight(row, "QuickReply.expandPinnedBanner", true);
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            expandedWhenPinned.setBoolean(row, previous);
            throw error;
        }
    }

    private void notifyBannerHeight(View row, String reason, boolean animate) throws ReflectiveOperationException {
        if (notifyHeightChanged.getParameterCount() == 2) {
            notifyHeightChanged.invoke(row, reason, animate);
        } else {
            notifyHeightChanged.invoke(row, animate);
        }
    }

    private void awaitExpandedBanner(BannerReplyAttempt reply, int attempt) {
        main.postDelayed(() -> guarded(() -> {
            View row = reply.row;
            Candidate target = bannerCandidate(row, reply.key);
            if (reply.request != requestId || target == null || !(boolean) isPinned.invoke(row)) {
                reply.clearOrigin();
                if (reply.backdrop != null) reply.backdrop.collapse();
                return;
            }
            if (bannerSwipes.containsKey(row) || bannerPreviews.containsKey(row)) {
                awaitExpandedBanner(reply, Math.max(1, attempt));
                return;
            }
            Object layout = getLayout.invoke(row);
            View expanded = layout == null ? null : (View) getChild.invoke(layout);
            View editor = layout == null ? null : (View) getEditor.invoke(layout);
            ReplyControl control = findBannerControl(target, expanded, editor);
            if (control != null && expanded.isShown() && !editorIsFinishingSend(editor)) {
                if ((boolean) isEditorActive.invoke(editor)) {
                    reply.clearOrigin();
                    reply.watchEditor(editor);
                    return;
                }
                if (activateEditor(control)) {
                    reply.watchEditor(editor);
                    reply.revealEditor(editor);
                    vibrateOnEditorFrame(row, editor, reply.request);
                    log(Log.INFO, TAG, "Opened reply in the expanded banner", null);
                    return;
                }
            } else if (attempt < BANNER_LAYOUT_RETRIES) {
                awaitExpandedBanner(reply, attempt + 1);
                return;
            }
            reply.clearOrigin();
            reply.restoreHeight();
            if (!openCompactBanner(target, reply, null) && reply.backdrop != null) reply.backdrop.collapse();
        }), attempt == 0 ? 16 : 32);
    }

    private ReplyControl findBannerControl(Candidate target, View template, View editor) {
        if (template == null || editor == null || !editor.isAttachedToWindow()
                || !isDescendantOf(editor, template)) return null;
        return findReplyControl(template, editor, target.replies());
    }

    private boolean openCompactBanner(Candidate target, BannerReplyAttempt reply,
                                      NotificationGesture pull) throws ReflectiveOperationException {
        Object layout = getLayout.invoke(target.row());
        if (layout == null) return false;
        View child = (View) getHeadsUpChild.invoke(layout);
        View editor = (View) headsUpEditor.get(layout);
        if (child == null || !child.isShown()) return false;
        ReplyControl control = findBannerControl(target, child, editor);
        if (control == null || editorIsFinishingSend(editor)) return false;
        if (editor.isShown() && (boolean) isEditorActive.invoke(editor)) {
            if (reply != null) reply.watchEditor(editor);
            return true;
        }
        int height = getActualHeight == null ? 0 : (int) getActualHeight.invoke(target.row());
        Rect replyButton = compactReplyBounds(target, child, height);
        if (!activateEditor(control)) return false;
        if (reply == null) reply = new BannerReplyAttempt(target);
        reply.trackPull(pull);
        reply.watchEditor(editor);
        ReplyReveal.showEditor(editor, target.row(), replyButton);
        vibrateOnEditorFrame(target.row(), editor, reply.request);
        log(Log.INFO, TAG, "Opened reply in the banner", null);
        return true;
    }

    private void closeBannerEditor(View editor) throws ReflectiveOperationException {
        closeEditor(editor, true);
    }

    private void closeEditor(View editor, boolean hideKeyboard) throws ReflectiveOperationException {
        if (editor == null || !editor.isShown()) return;
        Object editText = editorText.get(editor);
        if (editText == null) return;
        ReplyReveal.cancel(editor);
        if (hideKeyboard) {
            var keyboard = editor.getContext().getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(editor.getWindowToken(), 0);
        }
        // Use the controller's immediate close so a delayed exit cannot hide the next opening.
        defocusEditor.invoke(editText, false);
        // During a notification rebind the editor can still be visible with a defocused text field.
        if (editor.isShown() && forceEditorDefocus != null)
            forceEditorDefocus.invoke(editor, false, true, null);
    }

    // The manager falls back to the shade if its anchor does not resolve to this editor.
    private boolean selectsEditor(View anchor, View editor) {
        Object tag = editor.getTag();
        if (tag == null) return false;
        for (ViewParent parent = anchor.getParent(); parent instanceof View view;
                parent = parent.getParent()) {
            if (view.getId() == NOTIFICATION_CONTENT) return view.findViewWithTag(tag) == editor;
        }
        return false;
    }

    private static boolean isDescendantOf(View child, View ancestor) {
        for (ViewParent parent = child.getParent(); parent != null; parent = parent.getParent())
            if (parent == ancestor) return true;
        return false;
    }

    private void tryOpen(String key, int request, int attempt) {
        main.postDelayed(() -> {
            if (request != requestId) return;
            try {
                ViewGroup list = stack();
                if (list == null || !unlocked(list)) return;
                Candidate current = shadeReady(list)
                        ? findRowBelow(list, new Rect(), key) : null;
                if (current != null && openEditor(current)) {
                    log(Log.INFO, TAG, "Opened reply in the notification shade", null);
                    return;
                }
                if (attempt < OPEN_RETRIES) tryOpen(key, request, attempt + 1);
            } catch (ReflectiveOperationException | RuntimeException error) {
                warn(error);
            }
        }, attempt == 0 ? 35 : 75);
    }

    private boolean openEditor(Candidate target) throws ReflectiveOperationException {
        Object layout = getLayout.invoke(target.row());
        if (layout == null) return false;
        View editor = (View) getEditor.invoke(layout);
        if (editor == null || editorIsFinishingSend(editor)) return false;
        if (editor.isShown() && (boolean) isEditorActive.invoke(editor)) return true;
        View expanded = (View) getChild.invoke(layout);
        if (expanded == null || !expanded.isAttachedToWindow()) return false;
        ReplyControl control = findReplyControl(expanded, editor, target.replies());
        if (control == null) return false;
        boolean showingHistory = (boolean) isShowingExpanded.invoke(target.row());
        setUserExpanded.invoke(target.row(), true);
        if (!showingHistory) revealHistory(expanded, editor);
        if (!activateEditor(control)) return false;
        watchEditorBackdrop(ReplyBackdrop.forView(editor), editor);
        ReplyReveal.show(editor, true);
        vibrateOnEditorFrame(target.row(), editor, requestId);
        return true;
    }

    private void watchEditorBackdrop(ReplyBackdrop backdrop, View editor) {
        if (backdrop == null) return;
        backdrop.keepForEditor(editor, () -> {
            try {
                return editor.isShown() && (boolean) isEditorActive.invoke(editor);
            } catch (ReflectiveOperationException | RuntimeException error) {
                warn(error);
                return false;
            }
        });
    }

    private void installHistoryExpandClick(ClassLoader loader) {
        try {
            Class<?> listener = loader.loadClass(rowType.getName() + "$1");
            Field rowField = field(listener, "this$0");
            Method click = method(listener, "onClick", View.class);
            Method isExpanded = method(rowType, "isExpanded", boolean.class);
            hook(click).intercept(chain -> {
                View row = (View) rowField.get(chain.getThisObject());
                boolean wasExpanded = isHistoryExpanded(row, isExpanded);
                Object result = chain.proceed();
                if (!wasExpanded) guarded(() -> {
                    if (isHistoryExpanded(row, isExpanded) && candidate(row) != null) {
                        Object layout = getLayout.invoke(row);
                        if (layout != null)
                            revealHistory((View) getChild.invoke(layout), (View) getEditor.invoke(layout));
                    }
                });
                return result;
            });
        } catch (ReflectiveOperationException | RuntimeException error) {
            log(Log.WARN, TAG, "Native history expand hook unavailable", error);
        }
    }

    private boolean isHistoryExpanded(View row, Method isExpanded) throws ReflectiveOperationException {
        if (expandedWhenPinned != null && (boolean) isPinned.invoke(row))
            return expandedWhenPinned.getBoolean(row);
        return (boolean) isExpanded.invoke(row, false);
    }

    private static void revealHistory(View template, View editor) {
        ReplyReveal.show(findHistory(template, editor), false);
    }

    private static View findHistory(View template, View editor) {
        if (template == null) return null;
        for (int id : HISTORY_CONTENT) {
            View history = template.findViewById(id);
            if (history != null && (editor == null || (history != editor && !isDescendantOf(editor, history)))) {
                return history;
            }
        }
        return null;
    }

    private boolean editorIsFinishingSend(View editor) throws IllegalAccessException {
        return (editorResetting != null && editorResetting.getBoolean(editor))
                || (editorSending != null && editor.isShown() && editorSending.getBoolean(editor));
    }

    private View activeEditor(View row) throws ReflectiveOperationException {
        Object layout = getLayout.invoke(row);
        if (layout == null) return null;
        View expanded = (View) getEditor.invoke(layout);
        if (expanded != null && expanded.isShown() && (boolean) isEditorActive.invoke(expanded)) return expanded;
        View banner = (View) headsUpEditor.get(layout);
        if (banner != null && banner.isShown() && (boolean) isEditorActive.invoke(banner)) return banner;
        return null;
    }

    private View visibleEditor(View row) throws ReflectiveOperationException {
        View active = activeEditor(row);
        if (active != null) return active;
        Object layout = getLayout.invoke(row);
        if (layout == null) return null;
        View expanded = (View) getEditor.invoke(layout);
        if (expanded != null && expanded.isShown()) return expanded;
        View compact = (View) headsUpEditor.get(layout);
        return compact != null && compact.isShown() ? compact : null;
    }

    private static boolean touches(View view, MotionEvent event) {
        if (!view.isShown()) return false;
        Rect bounds = new Rect();
        int[] rootOrigin = new int[2];
        view.getRootView().getLocationOnScreen(rootOrigin);
        return view.getGlobalVisibleRect(bounds)
                && bounds.contains((int) (event.getRawX() - rootOrigin[0]),
                                   (int) (event.getRawY() - rootOrigin[1]));
    }

    private void vibrateOnEditorFrame(View row, View editor, int request) {
        row.postOnAnimation(() -> confirmVibration(row, editor, request, 4, false));
    }

    private void confirmVibration(View row, View editor, int request, int retries, boolean fullscreen) {
        guarded(() -> {
            if (request != requestId || !row.isAttachedToWindow() || !unlocked(row)) return;
            AudioManager audio = row.getContext().getSystemService(AudioManager.class);
            if (audio == null || audio.getRingerMode() != AudioManager.RINGER_MODE_VIBRATE) return;
            if (editor.isShown() && (fullscreen ? editor.hasFocus() : (boolean) isEditorActive.invoke(editor))) {
                Context context = row.getContext();
                if (Settings.System.getInt(context.getContentResolver(),
                        Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return;
                Vibrator vibrator = context.getSystemService(Vibrator.class);
                if (vibrator == null || !vibrator.hasVibrator()) return;
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK),
                        VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH));
                log(Log.INFO, TAG, "Reply haptic requested", null);
            } else if (retries > 0) {
                main.postDelayed(() -> confirmVibration(row, editor, request, retries - 1, fullscreen), 70);
            }
        });
    }

    private boolean activateEditor(ReplyControl control) throws ReflectiveOperationException {
        ReplyAction reply = control.reply();
        return (boolean) activate.invoke(remoteInputManager, control.anchor(),
                reply.action().getRemoteInputs(), reply.input(), reply.action().actionIntent, null);
    }

    private ReplyControl findReplyControl(View template, View editor, List<ReplyAction> replies) {
        for (ReplyAction reply : replies) {
            View anchor = findReplyButton(template, reply, replies.size() == 1);
            if (anchor != null && selectsEditor(anchor, editor)) {
                return new ReplyControl(anchor, reply);
            }
        }
        if (replies.size() == 1 && editor.isAttachedToWindow()
                && selectsEditor(editor, editor)) return new ReplyControl(editor, replies.get(0));
        return null;
    }

    private Rect compactReplyBounds(Candidate notification, View template, int compactHeight) {
        View actions = template == null ? null : template.findViewById(ACTIONS_CONTAINER_LAYOUT);
        return compactReplyBounds(notification.row(), compactReplyButton(notification, actions), actions, compactHeight);
    }

    private BannerTemplateTransition bannerTemplateTransition(Candidate notification, Object layout,
            int compactHeight, int expandedHeight)
            throws ReflectiveOperationException {
        if (compactHeight <= 0) return null;
        View compact = (View) getHeadsUpChild.invoke(layout);
        View actions = compact == null ? null : compact.findViewById(ACTIONS_CONTAINER_LAYOUT);
        View button = compactReplyButton(notification, actions);
        return BannerTemplateTransition.create(notification.row(), compact, (View) getChild.invoke(layout), button,
                compactReplyBounds(notification.row(), button, actions, compactHeight),
                notification.notification().getNotification(), compactHeight, expandedHeight, null, null, false);
    }

    private void rememberCompactActions(Candidate target) throws ReflectiveOperationException {
        View row = target.row();
        if (getActualHeight == null || expandedWhenPinned == null || expandedWhenPinned.getBoolean(row)
                || bannerPreviews.containsKey(row) || visibleEditor(row) != null) return;
        Object layout = getLayout.invoke(row);
        View compact = layout == null ? null : (View) getHeadsUpChild.invoke(layout);
        View actions = compact == null ? null : compact.findViewById(ACTIONS_CONTAINER_LAYOUT);
        if (actions == null || !actions.isShown()) return;
        int height = (int) getActualHeight.invoke(row);
        Rect bounds = compactActionBounds(row, actions, height);
        BannerTemplateTransition.ActionStrip strip = BannerTemplateTransition.ActionStrip.capture(actions, bounds);
        if (strip != null)
            compactBannerActions.put(row, new CompactActions(target.notification().getKey(), row.getWidth(),
                    height, strip));
    }

    private BannerTemplateTransition.ActionStrip compactActionsForTransition(
            View row, String key, View actions, int height) {
        CompactActions saved = compactBannerActions.get(row);
        if (saved != null && saved.key().equals(key) && saved.width() == row.getWidth()
                && saved.height() == height) return saved.strip();
        Rect bounds = compactActionBounds(row, actions, height);
        return bounds == null ? null : new BannerTemplateTransition.ActionStrip(null, bounds);
    }

    private static Rect compactActionBounds(View row, View actions, int compactHeight) {
        if (actions == null || actions.getWidth() <= 0 || actions.getHeight() <= 0
                || actions.getHeight() > compactHeight) return null;
        int[] position = new int[2];
        actions.getLocationInWindow(position);
        int x = position[0], y = position[1];
        row.getLocationInWindow(position);
        int left = x - position[0];
        int top = Math.max(0, Math.min(y - position[1], compactHeight - actions.getHeight()));
        return new Rect(left, top, left + actions.getWidth(), top + actions.getHeight());
    }

    private View compactReplyButton(Candidate notification, View actions) {
        if (actions == null || actions.getHeight() <= 0) return null;
        List<ReplyAction> replies = notification.replies();
        for (ReplyAction reply : replies) {
            View button = findReplyButton(actions, reply, replies.size() == 1);
            if (button != null && button.getVisibility() == View.VISIBLE
                    && button.getWidth() > 0 && button.getHeight() > 0) return button;
        }
        return null;
    }

    private static Rect compactReplyBounds(View row, View button, View actions, int compactHeight) {
        Rect strip = compactActionBounds(row, actions, compactHeight);
        if (button == null || strip == null) return null;
        int[] position = new int[2];
        button.getLocationInWindow(position);
        int x = position[0], y = position[1];
        actions.getLocationInWindow(position);
        int left = strip.left + x - position[0];
        int top = strip.top + y - position[1];
        return new Rect(left, top, left + button.getWidth(), top + button.getHeight());
    }

    private View findReplyButton(View view, ReplyAction reply, boolean unambiguous) {
        if (view.getTag(REMOTE_INPUT_TAG) instanceof RemoteInput[] inputs) {
            Object index = view.getTag(ACTION_INDEX_TAG);
            boolean sameAction = index instanceof Integer actionIndex
                    ? actionIndex == reply.index() : unambiguous;
            if (sameAction) {
                for (RemoteInput input : inputs) {
                    if (isTextReply(input) && reply.input().getResultKey().equals(input.getResultKey()))
                        return view;
                }
            }
        }
        if (view instanceof ViewGroup children) {
            for (int i = 0; i < children.getChildCount(); i++) {
                View found = findReplyButton(children.getChildAt(i), reply, unambiguous);
                if (found != null) return found;
            }
        }
        return null;
    }

    private Candidate findRowBelow(View view, Rect visible, String requested)
            throws ReflectiveOperationException {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (rowType.isInstance(view) && view.getGlobalVisibleRect(visible)) {
            Candidate match = candidate(view);
            if (match != null && requested.equals(match.notification().getKey())) return match;
        }
        if (view instanceof ViewGroup children) {
            for (int i = 0; i < children.getChildCount(); i++) {
                Candidate nested = findRowBelow(children.getChildAt(i), visible, requested);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private Candidate candidate(View row) throws ReflectiveOperationException {
        if (!row.isAttachedToWindow() || privateRow.getBoolean(row) || !unlocked(row)) return null;
        Object entry = getEntry.invoke(row);
        StatusBarNotification sbn = entry == null ? null : (StatusBarNotification) getSbn.invoke(entry);
        if (sbn == null) return null;
        Notification.Action[] actions = sbn.getNotification().actions;
        if (actions == null) return null;
        List<ReplyAction> replies = new ArrayList<>();
        for (int index = 0; index < actions.length; index++) {
            Notification.Action action = actions[index];
            if (action == null || action.actionIntent == null || action.actionIntent.isImmutable()
                    || action.getRemoteInputs() == null) continue;
            for (RemoteInput input : action.getRemoteInputs()) {
                if (!isTextReply(input)) continue;
                replies.add(new ReplyAction(index, action, input));
                break;
            }
        }
        if (replies.isEmpty()) return null;
        replies.sort((left, right) -> Boolean.compare(
                right.action().getSemanticAction() == Notification.Action.SEMANTIC_ACTION_REPLY,
                left.action().getSemanticAction() == Notification.Action.SEMANTIC_ACTION_REPLY));
        return new Candidate(row, sbn, replies);
    }

    private static boolean isTextReply(RemoteInput input) {
        return input != null && input.getAllowFreeFormInput() && input.getResultKey() != null;
    }

    private static boolean unlocked(View view) {
        KeyguardManager keyguard = view.getContext().getSystemService(KeyguardManager.class);
        return keyguard != null && !keyguard.isKeyguardLocked() && !keyguard.isDeviceLocked();
    }

    private ViewGroup stack() throws ReflectiveOperationException {
        if (panel == null) return null;
        Object controller = stackController.get(panel);
        return controller == null ? null : (ViewGroup) stackView.get(controller);
    }

    private boolean shadeReady(ViewGroup list) throws ReflectiveOperationException {
        return list.isAttachedToWindow() && list.isShown() && stackExpanded.getBoolean(list)
                && statusBarState.getInt(list) == 0;
    }

    private static Method method(Class<?> cls, String name, Class<?>... args)
            throws NoSuchMethodException {
        Method method = cls.getDeclaredMethod(name, args);
        method.setAccessible(true);
        return method;
    }

    private static Field field(Class<?> cls, String name) throws NoSuchFieldException {
        Field field = cls.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private interface CheckedAction {
        void run() throws ReflectiveOperationException;
    }

    private void guarded(CheckedAction action) {
        try {
            action.run();
        } catch (ReflectiveOperationException | RuntimeException error) {
            warn(error);
        }
    }

    private void warn(Exception error) {
        if (loggedError) return;
        loggedError = true;
        log(Log.WARN, TAG, "Unable to open a notification reply", error);
    }
}
