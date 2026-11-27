package io.shadereply;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.Person;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextDirectionHeuristics;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.CharacterStyle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.PathInterpolator;
import android.widget.TextView;

import java.util.WeakHashMap;

final class BannerTemplateTransition extends Drawable implements View.OnAttachStateChangeListener {
    private static final int APP_NAME = NotificationResources.id("app_name_text");
    private static final int MESSAGE_TEXT = NotificationResources.id("text");
    private static final int CONVERSATION_TEXT = NotificationResources.id("conversation_text");
    private static final int HEADER_TEXT_SECONDARY = NotificationResources.id("header_text_secondary");
    private static final int MESSAGING_CONTENT = NotificationResources.id("notification_messaging");
    private static final int MESSAGE_LINE = NotificationResources.id("message_text");
    private static final int MESSAGE_NAME = NotificationResources.id("message_name");
    private static final int MAX_SNAPSHOT_PIXELS = 420_000;
    private static final ArgbEvaluator COLORS = new ArgbEvaluator();
    private static final WeakHashMap<View, BannerTemplateTransition> active = new WeakHashMap<>();

    private final View card;
    private final View host;
    private final float density;
    private final Track title, message, groupSender;
    private final Snapshot compactApp, expandedApp, reply;
    private final Alpha liveActions;
    private final ActionStrip actionStrip;
    private final Rect movingReply;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Rect destination = new Rect();
    private final int[] position = new int[2];
    private ValueAnimator animator;
    private float progress;
    private int height;
    private boolean attached;

    static BannerTemplateTransition create(View card, View compact, View expanded, View replyButton,
            Rect replyBounds, Notification notification, int compactHeight, int expandedHeight,
            View actions, ActionStrip actionStrip, boolean replyMoves) {
        if (!ValueAnimator.areAnimatorsEnabled() || compact == null || expanded == null
                || compactHeight <= 0 || expandedHeight < compactHeight || card.getWidth() <= 0) return null;
        BannerTemplateTransition previous = active.get(card);
        if (previous != null) previous.finish();
        BannerTemplateTransition transition = new BannerTemplateTransition(card, compact, expanded,
                replyButton, replyBounds, notification, compactHeight, expandedHeight, actions, actionStrip, replyMoves);
        return transition.title == null && transition.message == null && transition.reply == null
                && actionStrip == null && !replyMoves ? null : transition;
    }

    private BannerTemplateTransition(View card, View compact, View expanded, View replyButton,
            Rect replyBounds, Notification notification, int compactHeight, int expandedHeight,
            View actions, ActionStrip actionStrip, boolean replyMoves) {
        this.card = card;
        host = card.getRootView();
        density = card.getResources().getDisplayMetrics().density;

        Bundle extras = notification.extras;
        Bundle[] messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES, Bundle.class);
        Bundle latest = messages == null || messages.length == 0 ? null : messages[messages.length - 1];
        CharSequence body = latest == null ? null : latest.getCharSequence("text");
        Person sender = latest == null ? null : latest.getParcelable("sender_person", Person.class);

        CharSequence conversation = first(extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE),
                extras.getCharSequence(Notification.EXTRA_TITLE), sender == null ? null : sender.getName());
        TextView compactTitle = headingView(compact, conversation);
        TextView expandedTitle = headingView(expanded, first(text(compactTitle), conversation));
        CharSequence heading = first(text(compactTitle), text(expandedTitle), conversation);
        title = track(heading, compactTitle, expandedTitle, compactHeight, expandedHeight, true);

        TextView summary = textView(compact, MESSAGE_TEXT);
        if (summary == null && !TextUtils.isEmpty(body)) summary = findText(compact, body, false);
        if (summary == null) summary = textView(compact, CONVERSATION_TEXT);
        if (summary == compactTitle) summary = null;
        View expandedMessages = expanded.findViewById(MESSAGING_CONTENT);
        TextView latestLine = TextUtils.isEmpty(body) ? null : findText(expandedMessages, body, true);
        if (latestLine == null && TextUtils.isEmpty(body)) latestLine = findText(expandedMessages, null, true);
        if (latestLine == null) {
            TextView paragraph = textView(expanded, MESSAGE_TEXT);
            if (TextUtils.equals(text(paragraph), body) || TextUtils.equals(text(paragraph), text(summary)))
                latestLine = paragraph;
        }
        CharSequence latestText = first(body, text(latestLine), text(summary), extras.getCharSequence(Notification.EXTRA_TEXT));
        String name = sender == null ? "" : senderName(sender.getName());
        TextView expandedSender = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION) && !name.isEmpty()
                ? findGroupSender(expandedMessages, latestLine, name) : null;
        Anchor compactSummary = anchor(summary, compactHeight);
        Anchor expandedNameAnchor = anchor(expandedSender, expandedHeight);
        int separator = senderPrefixEnd(text(summary), name);
        Anchor compactBody = bodyAnchor(summary, compactSummary, separator);
        if (expandedNameAnchor != null && compactBody != null && expandedSender != expandedTitle) {
            CharSequence summaryText = text(summary);
            char colon = summaryText.charAt(name.length());
            groupSender = new Track(name, name, compactSummary, expandedNameAnchor,
                    null, expandedSender, colon, senderColon(text(expandedSender), name));
            CharSequence compactText = summaryText.subSequence(separator, summaryText.length());
            Anchor expandedBody = anchor(latestLine, expandedHeight);
            if (expandedBody == null) expandedBody = fallback(compactBody, expandedHeight, 74f, 14f, false);
            message = new Track(latestText, compactText, compactBody, expandedBody, summary, latestLine, (char) 0, (char) 0);
        } else {
            groupSender = null;
            message = track(latestText, summary, latestLine, compactHeight, expandedHeight, false);
        }

        TextView compactName = textView(compact, APP_NAME), expandedName = textView(expanded, APP_NAME);
        compactApp = compactName == compactTitle ? null : snapshot(compactName);
        expandedApp = expandedName == expandedTitle ? null : snapshot(expandedName);
        this.actionStrip = actionStrip;
        movingReply = replyMoves ? replyBounds : null;
        liveActions = actionStrip != null ? new Alpha(actions) : replyMoves ? new Alpha(replyButton) : null;
        reply = actionStrip == null && !replyMoves ? Snapshot.capture(replyButton, replyBounds) : null;
    }

    void render(float progress, int height) {
        this.progress = Math.max(0f, Math.min(1f, progress));
        this.height = height;
        if (!attached) {
            if (!card.isAttachedToWindow() || !host.isAttachedToWindow()) return;
            attached = true;
            active.put(card, this);
            setBounds(0, 0, host.getWidth(), host.getHeight());
            card.addOnAttachStateChangeListener(this);
            host.getOverlay().add(this);
        }
        if (title != null) title.hide();
        if (groupSender != null) groupSender.hide();
        if (message != null) message.hide();
        hide(compactApp);
        hide(expandedApp);
        hide(reply);
        if (liveActions != null) liveActions.hide();
        invalidateSelf();
    }

    void finishOpening(int expandedHeight) {
        if (!card.isAttachedToWindow()) {
            finish();
            return;
        }
        animator = ValueAnimator.ofFloat(progress, 1f);
        animator.setDuration(210);
        animator.setInterpolator(new PathInterpolator(0.22f, 0.58f, 0.28f, 1f));
        int startHeight = height;
        animator.addUpdateListener(frame -> {
            int boundary = Math.round(lerp(startHeight, expandedHeight, frame.getAnimatedFraction()));
            render((float) frame.getAnimatedValue(), boundary);
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { finish(); }
        });
        animator.start();
    }

    void finish() {
        if (animator != null) {
            animator.removeAllListeners();
            animator.removeAllUpdateListeners();
            animator.cancel();
            animator = null;
        }
        if (!attached) return;
        attached = false;
        if (active.get(card) == this) active.remove(card);
        host.getOverlay().remove(this);
        card.removeOnAttachStateChangeListener(this);
        if (title != null) title.restore();
        if (groupSender != null) groupSender.restore();
        if (message != null) message.restore();
        restore(compactApp);
        restore(expandedApp);
        restore(reply);
        if (liveActions != null) liveActions.restore();
    }

    @Override public void draw(Canvas canvas) {
        if (!card.isShown() || card.getRootView() != host) return;
        host.getLocationInWindow(position);
        int hostX = position[0], hostY = position[1];
        card.getLocationInWindow(position);
        int saved = canvas.save();
        canvas.translate(position[0] - hostX, position[1] - hostY);
        canvas.clipRect(0, 0, card.getWidth(), height);

        float leave = smooth(progress / 0.55f);
        float arrive = smooth((progress - 0.54f) / 0.46f);
        if (compactApp != null) drawSnapshot(canvas, compactApp,
                compactApp.bounds.top - 10f * density * leave, 1f - leave);
        if (expandedApp != null) drawSnapshot(canvas, expandedApp,
                expandedApp.bounds.top + 10f * density * (1f - arrive), arrive);
        if (title != null) title.draw(canvas, progress);
        if (groupSender != null) groupSender.draw(canvas, progress);
        if (message != null) message.draw(canvas, progress);
        if (actionStrip != null) {
            float opacity = movingReply == null ? 1f - smooth((progress - 0.08f) / 0.37f)
                    : 1f - smooth((progress - 0.12f) / 0.58f);
            int controls = canvas.save();
            if (movingReply != null) canvas.clipOutRect(movingReply);
            drawActions(canvas, opacity);
            canvas.restoreToCount(controls);
        }
        if (reply != null) {
            float visible = 1f - smooth((progress - 0.07f) / 0.27f);
            drawSnapshot(canvas, reply, reply.bounds.top + 4f * density * (1f - visible), visible);
        }
        canvas.restoreToCount(saved);
    }

    private Track track(CharSequence value, TextView compact, TextView expanded, int compactHeight,
            int expandedHeight, boolean heading) {
        if (TextUtils.isEmpty(value)) return null;
        Anchor from = anchor(compact, compactHeight), to = anchor(expanded, expandedHeight);
        float compactSize = heading ? 15f : 14f;
        float compactBaseline = heading ? 25f : 44f;
        if (from == null) from = fallback(to, compactHeight, compactBaseline, compactSize, heading);
        if (to == null) {
            float end = Math.min(expandedHeight - 5f * density, from.baseline + (heading ? 19f : 31f) * density);
            to = fallback(from, expandedHeight, end / density, compactSize - 1f, heading);
        }
        CharSequence collapsed = text(compact);
        if (heading) {
            String name = senderName(value);
            if (name.isEmpty()) return null;
            char compactColon = senderColon(collapsed, name);
            char expandedColon = senderColon(text(expanded), name);
            return new Track(name, name, from, to, compact, expanded, compactColon, expandedColon);
        }
        if (TextUtils.isEmpty(collapsed) || !collapsed.toString().contains(value.toString())) collapsed = value;
        return new Track(value, collapsed, from, to, compact, expanded, (char) 0, (char) 0);
    }

    private Anchor anchor(TextView view, int height) {
        Rect rect = bounds(view);
        if (rect == null || view.getWidth() == 0 || view.getHeight() == 0 || view.getBaseline() < 0) return null;
        float baseline = rect.top + view.getBaseline();
        int left = rect.left + view.getCompoundPaddingLeft();
        int width = Math.min(card.getWidth() - left,
                view.getWidth() - view.getCompoundPaddingLeft() - view.getCompoundPaddingRight());
        if (left < 0 || width <= 0 || baseline <= 0f || baseline >= height) return null;
        return new Anchor(left, baseline, width, view.getTextSize(), view.getCurrentTextColor());
    }

    private Anchor bodyAnchor(TextView summary, Anchor anchor, int start) {
        if (anchor == null || start <= 0) return null;
        Layout layout = summary.getLayout();
        if (layout == null || start >= layout.getLineEnd(0)) return null;
        float offset = layout.getPrimaryHorizontal(start) - layout.getPrimaryHorizontal(0);
        return offset > 0 && offset < anchor.width
                ? new Anchor(anchor.left + offset, anchor.baseline, anchor.width - offset, anchor.size, anchor.color)
                : null;
    }

    private static int senderPrefixEnd(CharSequence summary, String name) {
        if (summary == null || name.isEmpty() || summary.length() <= name.length()
                || !TextUtils.regionMatches(summary, 0, name, 0, name.length())) return -1;
        int end = name.length();
        char separator = summary.charAt(end);
        if (separator != ':' && separator != '：') return -1;
        while (++end < summary.length() && Character.isWhitespace(summary.charAt(end))) {}
        return end < summary.length() ? end : -1;
    }

    private static TextView findGroupSender(View messages, TextView latestLine, String name) {
        if (messages == null) return null;
        for (View node = latestLine; node != null && node != messages;
                node = node.getParent() instanceof View parent ? parent : null) {
            TextView label = textView(node, MESSAGE_NAME);
            if (label != null && name.equals(senderName(text(label)))) return label;
        }
        TextView label = findHeading(messages, name);
        return label != null && label.getId() == MESSAGE_NAME ? label : null;
    }

    private Anchor fallback(Anchor other, int height, float baseline, float size, boolean heading) {
        float x = other == null ? Math.min(card.getWidth() * 0.28f, 56f * density) : other.left;
        TypedValue color = new TypedValue();
        card.getContext().getTheme().resolveAttribute(android.R.attr.textColorPrimary, color, true);
        int tint = other != null ? other.color : color.resourceId != 0
                ? card.getContext().getColor(color.resourceId) : color.data;
        if (Color.alpha(tint) == 0) tint = Color.WHITE;
        float baselinePx = Math.min(height - 3f * density, baseline * density);
        return new Anchor(x, Math.max(1f, baselinePx), Math.max(1f, card.getWidth() - x - 14f * density),
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, card.getResources().getDisplayMetrics()), tint);
    }

    private Rect bounds(View view) {
        if (view == null) return null;
        int left = 0, top = 0;
        for (View node = view; node != card; ) {
            left += node.getLeft();
            top += node.getTop();
            ViewParent parent = node.getParent();
            if (!(parent instanceof View parentView)) return null;
            left -= parentView.getScrollX();
            top -= parentView.getScrollY();
            node = parentView;
        }
        return new Rect(left, top, left + view.getWidth(), top + view.getHeight());
    }

    private Snapshot snapshot(View view) {
        return Snapshot.capture(view, bounds(view));
    }

    private void drawActions(Canvas canvas, float opacity) {
        if (opacity <= 0f) return;
        Rect bounds = actionStrip.bounds();
        int y = movingReply == null ? Math.round(bounds.top + 4f * density * (1f - opacity)) : bounds.top;
        int alpha = Math.round(opacity * 255f);
        Bitmap bitmap = actionStrip.bitmap();
        if (bitmap != null) {
            destination.set(bounds.left, y, bounds.right, y + bounds.height());
            paint.setAlpha(alpha);
            canvas.drawBitmap(bitmap, null, destination, paint);
        } else if (liveActions.view != null && liveActions.view.isAttachedToWindow()) {
            // Draw the strip outside its hidden template even when a software snapshot was unavailable.
            int saved = canvas.saveLayerAlpha(bounds.left, y, bounds.right, y + bounds.height(), alpha);
            try {
                canvas.translate(bounds.left, y);
                liveActions.view.draw(canvas);
            } finally {
                canvas.restoreToCount(saved);
            }
        }
    }

    private void drawSnapshot(Canvas canvas, Snapshot value, float y, float opacity) {
        int top = Math.round(y);
        destination.set(value.bounds.left, top, value.bounds.right, top + value.bounds.height());
        paint.setAlpha(Math.round(opacity * 255f));
        canvas.drawBitmap(value.bitmap, null, destination, paint);
    }

    private static TextView headingView(View template, CharSequence expected) {
        TextView title = textView(template, android.R.id.title);
        TextView secondary = textView(template, HEADER_TEXT_SECONDARY);
        TextView conversation = textView(template, CONVERSATION_TEXT);
        String name = senderName(expected);
        if (name.isEmpty()) return title;
        for (TextView label : new TextView[] {title, secondary, conversation}) {
            if (name.equals(senderName(text(label)))) return label;
        }
        TextView match = findHeading(template, name);
        return match == null ? title : match;
    }

    private static TextView findHeading(View view, String name) {
        if (view.getVisibility() == View.GONE) return null;
        int id = view.getId();
        if (view instanceof TextView label && id != APP_NAME && id != MESSAGE_LINE && id != MESSAGE_TEXT
                && name.equals(senderName(text(label)))) return label;
        if (view instanceof ViewGroup group) {
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                TextView match = findHeading(group.getChildAt(i), name);
                if (match != null) return match;
            }
        }
        return null;
    }

    private static String senderName(CharSequence text) {
        if (text == null) return "";
        String name = text.toString().strip();
        if (name.endsWith(":") || name.endsWith("：")) name = name.substring(0, name.length() - 1).stripTrailing();
        return name;
    }

    private static char senderColon(CharSequence text, String name) {
        if (text == null || !name.equals(senderName(text))) return 0;
        String raw = text.toString().stripTrailing();
        char last = raw.charAt(raw.length() - 1);
        return last == ':' || last == '：' ? last : 0;
    }

    private static TextView textView(View template, int id) {
        return template.findViewById(id) instanceof TextView view && !TextUtils.isEmpty(text(view)) ? view : null;
    }

    private static CharSequence text(TextView view) {
        return view == null || view.getVisibility() == View.GONE ? null : view.getText();
    }

    private static TextView findText(View view, CharSequence value, boolean messageOnly) {
        if (view == null || view.getVisibility() == View.GONE) return null;
        if (view instanceof TextView label && (!messageOnly || view.getId() == MESSAGE_LINE)
                && !TextUtils.isEmpty(text(label)) && (value == null || TextUtils.equals(text(label), value))) return label;
        if (view instanceof ViewGroup group) {
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                TextView match = findText(group.getChildAt(i), value, messageOnly);
                if (match != null) return match;
            }
        }
        return null;
    }

    private static CharSequence first(CharSequence... values) {
        for (CharSequence value : values) if (!TextUtils.isEmpty(value)) return value;
        return null;
    }

    private static void hide(Snapshot snapshot) {
        if (snapshot != null) snapshot.alpha.hide();
    }

    private static void restore(Snapshot snapshot) {
        if (snapshot != null) snapshot.alpha.restore();
    }

    private static float lerp(float start, float end, float fraction) {
        return start + (end - start) * fraction;
    }

    private static float smooth(float amount) {
        float p = Math.max(0f, Math.min(1f, amount));
        return p * p * (3f - 2f * p);
    }

    private record Anchor(float left, float baseline, float width, float size, int color) {}

    private static final class Track {
        private final CharSequence value, compactValue;
        private final Anchor compact, expanded;
        private final Alpha compactAlpha, expandedAlpha;
        private final TextPaint paint;
        private final int expandedLines;
        private final float baseSize;
        private final ColonFade colon;
        private StaticLayout layout;
        private int layoutWidth, layoutLines;
        private CharSequence layoutValue;

        Track(CharSequence value, CharSequence compactValue, Anchor compact, Anchor expanded,
                TextView compactView, TextView expandedView, char compactColon, char expandedColon) {
            char separator = compactColon != 0 ? compactColon : expandedColon;
            if (separator == 0) {
                colon = null;
                this.value = TextUtils.stringOrSpannedString(value);
                this.compactValue = TextUtils.stringOrSpannedString(compactValue);
            } else {
                colon = new ColonFade(compactColon != 0, expandedColon != 0);
                SpannableString name = new SpannableString(value.toString() + separator);
                name.setSpan(colon, value.length(), name.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                this.value = this.compactValue = name;
            }
            this.compact = compact;
            this.expanded = expanded;
            compactAlpha = new Alpha(compactView);
            expandedAlpha = new Alpha(expandedView == compactView ? null : expandedView);
            TextView style = expandedView == null ? compactView : expandedView;
            paint = style == null ? new TextPaint(Paint.ANTI_ALIAS_FLAG) : new TextPaint(style.getPaint());
            if (style == null) paint.setTypeface(Typeface.DEFAULT);
            baseSize = Math.max(1f, expanded.size);
            paint.setTextSize(baseSize);
            Layout realLayout = expandedView == null ? null : expandedView.getLayout();
            expandedLines = realLayout == null ? 1 : Math.max(1, realLayout.getLineCount());
        }

        void draw(Canvas canvas, float progress) {
            float x = lerp(compact.left, expanded.left, progress);
            float baseline = lerp(compact.baseline, expanded.baseline, progress);
            float scale = Math.max(0.01f, lerp(compact.size, expanded.size, progress) / baseSize);
            int width = Math.max(1, Math.round(lerp(compact.width, expanded.width, progress) / scale));
            float extent = smooth((progress - 0.12f) / 0.81f);
            int lines = extent == 0f ? 1 : expandedLines;
            CharSequence displayed = progress == 0f ? compactValue : value;
            if (layout == null || width != layoutWidth || lines != layoutLines || displayed != layoutValue) {
                layout = StaticLayout.Builder.obtain(displayed, 0, displayed.length(), paint, width)
                        .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
                        .setIncludePad(false)
                        .setMaxLines(lines)
                        .setEllipsize(TextUtils.TruncateAt.END)
                        .build();
                layoutWidth = width;
                layoutLines = lines;
                layoutValue = displayed;
            }
            paint.setColor((int) COLORS.evaluate(progress, compact.color, expanded.color));
            if (colon != null) colon.opacity = lerp(colon.compact, colon.expanded, progress);
            int saved = canvas.save();
            canvas.translate(x, baseline);
            canvas.scale(scale, scale);
            canvas.translate(0, -layout.getLineBaseline(0));
            int firstBottom = layout.getLineBottom(0);
            canvas.clipRect(0, 0, width, lerp(firstBottom, layout.getHeight(), extent));
            layout.draw(canvas);
            canvas.restoreToCount(saved);
        }

        void hide() {
            compactAlpha.hide();
            expandedAlpha.hide();
        }

        void restore() {
            compactAlpha.restore();
            expandedAlpha.restore();
        }
    }

    private static final class ColonFade extends CharacterStyle {
        final float compact, expanded;
        float opacity;

        ColonFade(boolean compact, boolean expanded) {
            this.compact = compact ? 1f : 0f;
            this.expanded = expanded ? 1f : 0f;
        }

        @Override public void updateDrawState(TextPaint paint) {
            paint.setAlpha(Math.round(paint.getAlpha() * opacity));
        }
    }

    private static final class Alpha {
        private final View view;
        private boolean hidden;
        private float previous;

        Alpha(View view) {
            this.view = view;
        }

        void hide() {
            if (view == null) return;
            float alpha = view.getTransitionAlpha();
            if (!hidden || alpha != 0f) previous = alpha;
            view.setTransitionAlpha(0f);
            hidden = true;
        }

        void restore() {
            if (view != null && hidden && view.getTransitionAlpha() == 0f) view.setTransitionAlpha(previous);
            hidden = false;
        }
    }

    record ActionStrip(Bitmap bitmap, Rect bounds) {
        static ActionStrip capture(View view, Rect bounds) {
            Bitmap bitmap = captureBitmap(view, bounds);
            return bitmap == null ? null : new ActionStrip(bitmap, bounds);
        }
    }

    private record Snapshot(Alpha alpha, Bitmap bitmap, Rect bounds) {
        static Snapshot capture(View view, Rect bounds) {
            Bitmap bitmap = captureBitmap(view, bounds);
            return bitmap == null ? null : new Snapshot(new Alpha(view), bitmap, new Rect(bounds));
        }
    }

    private static Bitmap captureBitmap(View view, Rect bounds) {
        if (view == null || bounds == null || bounds.isEmpty() || view.getVisibility() == View.GONE
                || (long) bounds.width() * bounds.height() > MAX_SNAPSHOT_PIXELS) return null;
        try {
            Bitmap bitmap = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888);
            view.draw(new Canvas(bitmap));
            return bitmap;
        } catch (RuntimeException unsupported) {
            return null;
        }
    }

    @Override public void onViewAttachedToWindow(View view) {}
    @Override public void onViewDetachedFromWindow(View view) { finish(); }
    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(ColorFilter colorFilter) {}
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
