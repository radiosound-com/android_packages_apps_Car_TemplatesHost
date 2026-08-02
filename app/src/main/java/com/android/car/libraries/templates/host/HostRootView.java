package com.android.car.libraries.templates.host;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Path;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.VelocityTracker;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.car.app.model.Action;
import androidx.car.app.model.CarText;
import androidx.car.app.model.Item;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.MessageTemplate;
import androidx.car.app.OnDoneCallback;
import androidx.car.app.model.Pane;
import androidx.car.app.model.PaneTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.SectionedItemList;
import androidx.car.app.model.SearchTemplate;
import androidx.car.app.model.Template;
import androidx.car.app.model.TemplateWrapper;
import androidx.car.app.navigation.model.MapWithContentTemplate;
import androidx.car.app.navigation.model.MapTemplate;
import androidx.car.app.navigation.model.NavigationTemplate;
import androidx.car.app.navigation.model.PlaceListNavigationTemplate;

import java.util.ArrayList;
import java.util.List;

/** Surface content used by the open templates host. */
final class HostRootView extends FrameLayout {
    final MapSurfaceView mapSurface;
    final TemplateCanvasView templateView;
    final int densityDpi;

    HostRootView(Context context, int densityDpi, TemplatesHostService.RendererSession session) {
        super(context);
        this.densityDpi = densityDpi;
        setBackgroundColor(Color.TRANSPARENT);
        mapSurface = new MapSurfaceView(context, session);
        addView(mapSurface, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        templateView = new TemplateCanvasView(context, session, densityDpi);
        addView(templateView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    void render(TemplateWrapper wrapper) {
        templateView.render(wrapper);
        Template template = wrapper == null ? null : wrapper.getTemplate();
        boolean map = template instanceof MapTemplate
                || template instanceof MapWithContentTemplate
                || template instanceof NavigationTemplate
                || template instanceof PlaceListNavigationTemplate;
        mapSurface.setVisibility(map ? VISIBLE : INVISIBLE);
        templateView.setMapMode(map);
    }

    void setWindowInsets(Insets insets, Insets stableInsets) {
        templateView.setWindowInsets(insets, stableInsets);
    }

    void showToast(CharSequence text) {
        templateView.showToast(text);
    }

    void destroy() {
        mapSurface.destroy();
    }

    static final class MapSurfaceView extends SurfaceView implements SurfaceHolder.Callback {
        private final TemplatesHostService.RendererSession session;
        private boolean ready;

        MapSurfaceView(Context context, TemplatesHostService.RendererSession session) {
            super(context);
            this.session = session;
            getHolder().addCallback(this);
            setZOrderMediaOverlay(false);
        }

        boolean isReady() {
            return ready && getHolder().getSurface() != null && getHolder().getSurface().isValid();
        }

        @Override public void surfaceCreated(SurfaceHolder holder) {
            ready = true;
            session.publishMapSurface();
        }

        @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            if (ready) session.publishMapSurface();
        }

        @Override public void surfaceDestroyed(SurfaceHolder holder) {
            ready = false;
        }

        void destroy() {
            ready = false;
            getHolder().removeCallback(this);
        }
    }

    static final class TemplateCanvasView extends android.view.View {
        private static final int BG = Color.rgb(18, 20, 24);
        private static final int PANEL = Color.rgb(57, 57, 57);
        private static final int PANEL_ALT = Color.rgb(68, 68, 68);
        private static final int DIVIDER = Color.rgb(76, 76, 76);
        private static final int TEXT = Color.rgb(232, 232, 232);
        private static final int MUTED = Color.rgb(183, 183, 183);
        private static final int ACCENT = Color.rgb(118, 183, 255);
        private static final int ICON = Color.rgb(245, 245, 245);

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TemplatesHostService.RendererSession session;
        private final List<Hit> hits = new ArrayList<>();
        private TemplateWrapper wrapper;
        private boolean mapMode;
        private Insets windowInsets = Insets.NONE;
        private Insets stableInsets = Insets.NONE;
        private String toast;
        private float listScrollOffset;
        private float downX;
        private float downY;
        private float lastX;
        private float lastY;
        private boolean dragging;
        private boolean listDragging;
        private boolean backPressed;
        private Hit pressedHit;
        private VelocityTracker velocityTracker;
        private float listMaxScroll;

        TemplateCanvasView(Context context, TemplatesHostService.RendererSession session,
                           int densityDpi) {
            super(context);
            this.session = session;
            setFocusable(true);
            paint.setTypeface(Typeface.create("sans", Typeface.NORMAL));
        }

        void setMapMode(boolean mapMode) {
            this.mapMode = mapMode;
            invalidate();
        }

        void render(TemplateWrapper wrapper) {
            if (this.wrapper != wrapper) {
                listScrollOffset = 0;
            }
            this.wrapper = wrapper;
            invalidate();
        }

        void setWindowInsets(Insets insets, Insets stableInsets) {
            this.windowInsets = insets == null ? Insets.NONE : insets;
            this.stableInsets = stableInsets == null ? Insets.NONE : stableInsets;
            invalidate();
        }

        void showToast(CharSequence text) {
            toast = text == null ? null : text.toString();
            invalidate();
            removeCallbacks(clearToast);
            if (toast != null) {
                postDelayed(clearToast, 3500L);
            }
        }

        private final Runnable clearToast = () -> {
            toast = null;
            invalidate();
        };

        private float dp(float value) {
            // The AndroidX surface reports a renderer density that is not the
            // physical Automotive display density on this profile. The stock
            // host's 520dp panel resolves to 390px at 1080x600, so scale the
            // design metrics from the reference display and keep them adaptive.
            return value * (getWidth() / 1080f) * .75f;
        }

        private float contentTop() {
            return Math.max(0, windowInsets.top);
        }

        private float contentBottom() {
            return getHeight() - Math.max(0, windowInsets.bottom);
        }

        private float panelTop() {
            return contentTop() + dp(12);
        }

        private float panelBottom() {
            return contentBottom() - dp(25);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            hits.clear();
            Template template = wrapper == null ? null : wrapper.getTemplate();
            if (!mapMode) {
                canvas.drawColor(BG);
            } else {
                // Leave the map surface visible and only paint the overlay/panel.
                canvas.drawColor(Color.TRANSPARENT);
            }
            if (template == null) {
                text(canvas, "Waiting for car app template…", 32, 52, 22, TEXT);
                return;
            }

            if (template instanceof MapWithContentTemplate) {
                drawMapWithContent(canvas, (MapWithContentTemplate) template);
            } else if (template instanceof PlaceListNavigationTemplate) {
                drawPlaceListNavigation(canvas, (PlaceListNavigationTemplate) template);
            } else if (template instanceof MapTemplate) {
                drawMapTemplate(canvas, (MapTemplate) template);
            } else if (template instanceof NavigationTemplate) {
                drawNavigationTemplate(canvas, (NavigationTemplate) template);
            } else if (template instanceof ListTemplate) {
                drawListTemplate(canvas, (ListTemplate) template);
            } else if (template instanceof PaneTemplate) {
                drawPaneTemplate(canvas, (PaneTemplate) template);
            } else if (template instanceof MessageTemplate) {
                drawMessageTemplate(canvas, (MessageTemplate) template);
            } else if (template instanceof SearchTemplate) {
                drawSearchTemplate(canvas, (SearchTemplate) template);
            } else {
                text(canvas, template.getClass().getSimpleName(), 32, 58, 22, TEXT);
                text(canvas, "This Caramel Vanilla host does not render this template yet.",
                        32, 94, 16, MUTED);
            }
        }

        private void drawMapWithContent(Canvas canvas, MapWithContentTemplate template) {
            drawContentPanel(canvas, template.getContentTemplate(), true);
            drawActionStrip(canvas, template.getActionStrip());
        }

        private void drawPlaceListNavigation(Canvas canvas, PlaceListNavigationTemplate template) {
            float left = dp(12);
            float top = panelTop();
            float right = Math.min(getWidth() - dp(12), left + dp(520));
            float bottom = panelBottom();
            drawPanel(canvas, left, top, right, bottom);
            drawAppHeader(canvas, template.getTitle(), left, top, right);
            drawRows(canvas, template.getItemList(), left, top + dp(84), right, true);
            drawMapActionStrip(canvas, template.getActionStrip(), top + dp(42));
            drawMapActionStack(canvas, template.getMapActionStrip(), top, bottom);
            drawToast(canvas);
        }

        private void drawMapTemplate(Canvas canvas, MapTemplate template) {
            if (template.getPane() != null) {
                drawPane(canvas, template.getPane(), 26, 26, Math.min(getWidth() - 52, 620));
            } else if (template.getItemList() != null) {
                drawItemsPanel(canvas, template.getItemList());
            }
            drawActionStrip(canvas, template.getActionStrip());
        }

        private void drawNavigationTemplate(Canvas canvas, NavigationTemplate template) {
            float left = dp(12);
            float bottom = panelBottom();
            float top = Math.max(panelTop(), bottom - dp(168));
            float right = Math.min(getWidth() - dp(12), left + dp(520));
            drawPanel(canvas, left, top, right, bottom);
            text(canvas, "Navigation", left + dp(32), top + dp(53), dp(27), TEXT);
            text(canvas, "Map surface supplied to the app", left + dp(32),
                    top + dp(88), dp(19), MUTED);
            drawMapActionStrip(canvas, template.getActionStrip(), panelTop() + dp(42));
            drawMapActionStack(canvas, template.getMapActionStrip(), panelTop(), bottom);
            drawToast(canvas);
        }

        private void drawListTemplate(Canvas canvas, ListTemplate template) {
            float toolbarTop = contentTop();
            drawToolbar(canvas, template, toolbarTop);
            float listTop = toolbarTop + dp(115) - listScrollOffset;
            float listBottom = contentBottom();
            float contentEnd;
            canvas.save();
            canvas.clipRect(0, toolbarTop + dp(78), getWidth(), listBottom);
            if (template.getSingleList() != null) {
                drawSectionHeader(canvas, template.getTitle(), listTop);
                contentEnd = drawSettingsRows(canvas, template.getSingleList(), listTop + dp(17));
            } else {
                contentEnd = listTop;
                for (SectionedItemList section : template.getSectionedLists()) {
                    drawSectionHeader(canvas, section.getHeader(), contentEnd);
                    contentEnd = drawSettingsRows(canvas, section.getItemList(), contentEnd + dp(17));
                }
            }
            canvas.restore();
            if (listScrollOffset > 0) {
                drawScrollChevron(canvas, dp(42), toolbarTop + dp(120), true);
            }
            if (contentEnd > listBottom) {
                drawScrollChevron(canvas, dp(42), listBottom - dp(42), false);
            }
            listMaxScroll = Math.max(0, contentEnd + listScrollOffset - listBottom);
            drawToast(canvas);
        }

        private void drawToolbar(Canvas canvas, ListTemplate template, float top) {
            boolean back = template.getHeaderAction() != null
                    && template.getHeaderAction().getType() == Action.TYPE_BACK;
            if (back) {
                drawBackArrow(canvas, dp(53), top + dp(40));
                addBackHit(top);
                text(canvas, "Settings", dp(104), top + dp(51), dp(27), TEXT);
            } else {
                text(canvas, textOf(template.getTitle()), dp(32), top + dp(51), dp(27), TEXT);
            }
        }

        private void drawSectionHeader(Canvas canvas, @Nullable CarText header, float baseline) {
            textBold(canvas, textOf(header), dp(88), baseline, dp(24), TEXT);
            drawScrollChevron(canvas, dp(42), baseline - dp(2), true);
        }

        private float drawSettingsRows(Canvas canvas, @Nullable ItemList list, float y) {
            if (list == null) return y;
            for (Item item : list.getItems()) {
                if (!(item instanceof Row)) continue;
                Row row = (Row) item;
                float rowTop = y;
                boolean hasText = !row.getTexts().isEmpty();
                float rowHeight = hasText ? dp(112) : dp(62);
                text(canvas, textOf(row.getTitle()), dp(88), rowTop + dp(40), dp(27),
                        row.isEnabled() ? TEXT : MUTED);
                    float textY = rowTop + dp(69);
                for (CarText subtext : row.getTexts()) {
                    textY = drawWrappedText(canvas, textOf(subtext), dp(88), textY,
                            getWidth() - dp(175), dp(21), MUTED);
                }
                if (row.getToggle() != null) {
                    drawToggle(canvas, getWidth() - dp(115),
                            rowTop + (hasText ? dp(47) : dp(30)), row.getToggle().isChecked());
                    if (row.getToggle().getOnCheckedChangeDelegate() != null) {
                        addToggleHit(dp(70), rowTop, getWidth() - dp(70), rowTop + rowHeight,
                                row.getToggle().getOnCheckedChangeDelegate(),
                                row.getToggle().isChecked());
                    }
                }
                if (row.getOnClickDelegate() != null) {
                    addHit(dp(70), rowTop, getWidth() - dp(70), rowTop + rowHeight,
                            row.getOnClickDelegate());
                }
                paint.setColor(DIVIDER);
                canvas.drawRect(dp(88), rowTop + rowHeight - 1,
                        getWidth() - dp(88), rowTop + rowHeight, paint);
                y += rowHeight;
            }
            return y;
        }

        private float drawWrappedText(Canvas canvas, String value, float x, float y,
                                      float maxWidth, float size, int color) {
            if (value == null || value.isEmpty()) return y;
            paint.setTextSize(size);
            String line = "";
            for (String word : value.split("\\s+")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (!line.isEmpty() && paint.measureText(candidate) > maxWidth) {
                    text(canvas, line, x, y, size, color);
                    y += dp(26);
                    line = word;
                } else {
                    line = candidate;
                }
            }
            if (!line.isEmpty()) {
                text(canvas, line, x, y, size, color);
                y += dp(26);
            }
            return y;
        }

        private void drawPaneTemplate(Canvas canvas, PaneTemplate template) {
            drawPanel(canvas, 24, 20, Math.min(getWidth() - 24, 650), getHeight() - 28);
            title(canvas, template.getTitle(), 48, 62);
            drawPane(canvas, template.getPane(), 48, 94, Math.min(getWidth() - 56, 590));
            drawAction(canvas, template.getHeaderAction(), 48, 28, 210, 72);
            drawActionStrip(canvas, template.getActionStrip());
        }

        private void drawMessageTemplate(Canvas canvas, MessageTemplate template) {
            drawPanel(canvas, 24, 20, Math.min(getWidth() - 24, 620), getHeight() - 28);
            title(canvas, template.getTitle(), 48, 62);
            text(canvas, textOf(template.getMessage()), 48, 128, 20, TEXT);
            drawAction(canvas, template.getHeaderAction(), 48, 28, 210, 72);
            drawActionList(canvas, template.getActions(), 48, 170);
            drawActionStrip(canvas, template.getActionStrip());
        }

        private void drawSearchTemplate(Canvas canvas, SearchTemplate template) {
            drawPanel(canvas, 24, 20, Math.min(getWidth() - 24, 620), getHeight() - 28);
            text(canvas, "Search", 48, 62, 24, TEXT);
            drawPanel(canvas, 48, 84, Math.min(getWidth() - 56, 570), 136);
            text(canvas, template.getSearchHint(), 72, 116, 17, MUTED);
            text(canvas, template.getInitialSearchText(), 72, 158, 20, TEXT);
            drawItems(canvas, template.getItemList(), 48, 258, Math.min(getWidth() - 56, 570));
            drawAction(canvas, template.getHeaderAction(), 48, 28, 210, 72);
            drawActionStrip(canvas, template.getActionStrip());
        }

        private void drawAppHeader(Canvas canvas, @Nullable CarText title,
                                   float left, float top, float right) {
            float centerX = left + dp(35);
            float centerY = top + dp(42);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(70, 70, 70));
            canvas.drawCircle(centerX, centerY, dp(23), paint);
            drawPin(canvas, centerX, centerY, dp(16));
            text(canvas, textOf(title), left + dp(70), top + dp(52), dp(27), TEXT);
            paint.setColor(DIVIDER);
            canvas.drawRect(left, top + dp(84) - 1, right, top + dp(84), paint);
        }

        private void drawRows(Canvas canvas, @Nullable ItemList list, float left, float top,
                              float right, boolean withIcons) {
            if (list == null) return;
            int index = 0;
            for (Item item : list.getItems()) {
                if (!(item instanceof Row)) continue;
                Row row = (Row) item;
                float rowTop = top + index * dp(84);
                float rowBottom = rowTop + dp(84);
                if (rowTop >= panelBottom()) break;
                if (withIcons) {
                    drawRowIcon(canvas, index, left + dp(44), rowTop + dp(42));
                }
                text(canvas, textOf(row.getTitle()), left + dp(94), rowTop + dp(53),
                        dp(27), row.isEnabled() ? TEXT : MUTED);
                if (!row.getTexts().isEmpty()) {
                    text(canvas, textOf(row.getTexts().get(0)), left + dp(94),
                            rowTop + dp(72), dp(15), MUTED);
                }
                drawChevron(canvas, right - dp(44), rowTop + dp(42));
                paint.setColor(DIVIDER);
                canvas.drawRect(left + dp(12), rowBottom - 1, right - dp(12), rowBottom, paint);
                addHit(left, rowTop, right, rowBottom, row.getOnClickDelegate());
                index++;
            }
        }

        private void drawRowIcon(Canvas canvas, int index, float x, float y) {
            paint.setColor(ICON);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(3));
            paint.setStrokeCap(Paint.Cap.ROUND);
            switch (index) {
                case 0:
                    PathHelper.drawNavigationArrow(canvas, paint, x, y, dp(16));
                    break;
                case 1:
                    canvas.drawCircle(x, y, dp(14), paint);
                    canvas.drawLine(x - dp(14), y, x - dp(3), y, paint);
                    canvas.drawLine(x - dp(14), y, x - dp(7), y - dp(8), paint);
                    break;
                case 2:
                    canvas.drawCircle(x, y, dp(14), paint);
                    paint.setStyle(Paint.Style.FILL);
                    text(canvas, "i", x - dp(4), y + dp(8), dp(23), ICON);
                    break;
                case 3:
                    PathHelper.drawStar(canvas, paint, x, y, dp(16));
                    break;
                case 4:
                    canvas.drawLine(x - dp(8), y - dp(15), x - dp(8), y + dp(15), paint);
                    PathHelper.drawFlag(canvas, paint, x - dp(5), y - dp(11), dp(17));
                    break;
                default:
                    canvas.drawCircle(x - dp(7), y, dp(5), paint);
                    canvas.drawCircle(x + dp(7), y, dp(5), paint);
                    canvas.drawLine(x - dp(2), y - dp(5), x + dp(2), y - dp(5), paint);
                    canvas.drawLine(x - dp(2), y + dp(5), x + dp(2), y + dp(5), paint);
                    break;
            }
            paint.setStrokeCap(Paint.Cap.BUTT);
            paint.setStyle(Paint.Style.FILL);
        }

        private void drawMapActionStrip(Canvas canvas,
                                        @Nullable androidx.car.app.model.ActionStrip strip,
                                        float centerY) {
            if (strip == null) return;
            List<Action> actions = strip.getActions();
            float centerX = getWidth() - dp(53);
            for (int i = actions.size() - 1; i >= 0; i--) {
                Action action = actions.get(i);
                float x = centerX - (actions.size() - 1 - i) * dp(94);
                paint.setColor(PANEL);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(x, centerY, dp(40), paint);
                drawActionIcon(canvas, action, x, centerY, x < centerX);
                addHit(x - dp(40), centerY - dp(40), x + dp(40), centerY + dp(40),
                        action.getOnClickDelegate());
            }
        }

        private void drawMapActionStack(Canvas canvas,
                                        @Nullable androidx.car.app.model.ActionStrip strip,
                                        float top, float bottom) {
            if (strip == null) return;
            List<Action> actions = strip.getActions();
            float x = getWidth() - dp(53);
            float center = top + (bottom - top) * .64f;
            for (int i = 0; i < actions.size(); i++) {
                Action action = actions.get(i);
                float y = center + i * dp(92);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(PANEL);
                canvas.drawCircle(x, y, dp(40), paint);
                drawMapStackIcon(canvas, action, i, x, y);
                addHit(x - dp(40), y - dp(40), x + dp(40), y + dp(40),
                        action.getOnClickDelegate());
            }
        }

        private void drawMapStackIcon(Canvas canvas, Action action, int index, float x, float y) {
            paint.setColor(ICON);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(3));
            if (action.getType() == Action.TYPE_PAN) {
                canvas.drawLine(x - dp(13), y, x + dp(13), y, paint);
                canvas.drawLine(x, y - dp(13), x, y + dp(13), paint);
            } else if (index == 0) {
                canvas.drawCircle(x, y, dp(11), paint);
                canvas.drawLine(x - dp(17), y, x + dp(17), y, paint);
                canvas.drawLine(x, y - dp(17), x, y + dp(17), paint);
            } else if (index == 1) {
                canvas.drawLine(x - dp(14), y, x + dp(14), y, paint);
                canvas.drawLine(x, y - dp(14), x, y + dp(14), paint);
            } else {
                canvas.drawLine(x - dp(14), y, x + dp(14), y, paint);
            }
            paint.setStyle(Paint.Style.FILL);
        }

        private void drawMapControls(Canvas canvas, float top, float bottom) {
            float x = getWidth() - dp(53);
            float center = top + (bottom - top) * .64f;
            drawControl(canvas, x, center, 0);
            drawControl(canvas, x, center + dp(92), 1);
            drawControl(canvas, x, center + dp(184), 2);
        }

        private void drawControl(Canvas canvas, float x, float y, int type) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(PANEL);
            canvas.drawCircle(x, y, dp(40), paint);
            paint.setColor(ICON);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(3));
            if (type == 0) {
                canvas.drawCircle(x, y, dp(11), paint);
                canvas.drawLine(x - dp(17), y, x + dp(17), y, paint);
                canvas.drawLine(x, y - dp(17), x, y + dp(17), paint);
            } else if (type == 1) {
                canvas.drawLine(x - dp(14), y, x + dp(14), y, paint);
                canvas.drawLine(x, y - dp(14), x, y + dp(14), paint);
            } else {
                canvas.drawLine(x - dp(14), y, x + dp(14), y, paint);
            }
            paint.setStyle(Paint.Style.FILL);
        }

        private void drawActionIcon(Canvas canvas, Action action, float x, float y,
                                    boolean settings) {
            String label = textOf(action.getTitle()).toLowerCase();
            paint.setColor(ICON);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(3));
            if (settings || label.contains("setting")) {
                canvas.drawCircle(x, y, dp(11), paint);
                for (int i = 0; i < 8; i++) {
                    double angle = i * Math.PI / 4;
                    canvas.drawLine(x + (float) Math.cos(angle) * dp(14),
                            y + (float) Math.sin(angle) * dp(14),
                            x + (float) Math.cos(angle) * dp(19),
                            y + (float) Math.sin(angle) * dp(19), paint);
                }
            } else {
                canvas.drawCircle(x - dp(3), y - dp(3), dp(10), paint);
                canvas.drawLine(x + dp(5), y + dp(5), x + dp(16), y + dp(16), paint);
            }
            paint.setStyle(Paint.Style.FILL);
        }

        private void drawToast(Canvas canvas) {
            if (toast == null || toast.isEmpty()) return;
            float left = dp(428);
            float right = Math.min(getWidth() - dp(40), dp(1009));
            float bottom = panelBottom();
            float top = bottom - dp(132);
            paint.setColor(Color.rgb(224, 229, 236));
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(left, top, right, bottom, dp(52), dp(52), paint);
            text(canvas, toast, left + dp(25), top + dp(58), dp(23), Color.rgb(55, 55, 55));
        }

        private void drawToggle(Canvas canvas, float centerX, float centerY, boolean checked) {
            float width = dp(69);
            float height = dp(32);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(checked ? Color.WHITE : Color.rgb(83, 83, 83));
            canvas.drawRoundRect(centerX - width / 2, centerY - height / 2,
                    centerX + width / 2, centerY + height / 2, height / 2, height / 2, paint);
            if (!checked) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2));
                paint.setColor(Color.rgb(155, 155, 155));
                canvas.drawRoundRect(centerX - width / 2, centerY - height / 2,
                        centerX + width / 2, centerY + height / 2, height / 2, height / 2, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(checked ? Color.rgb(45, 45, 45) : Color.rgb(180, 180, 180));
            float thumb = dp(22);
            float thumbX = checked ? centerX + width / 2 - thumb : centerX - width / 2 + thumb;
            canvas.drawCircle(thumbX, centerY, dp(11), paint);
        }

        private void drawBackArrow(Canvas canvas, float x, float y) {
            paint.setColor(Color.rgb(205, 205, 205));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(3));
            paint.setStrokeCap(Paint.Cap.SQUARE);
            canvas.drawLine(x + dp(10), y, x - dp(10), y, paint);
            canvas.drawLine(x - dp(10), y, x, y - dp(10), paint);
            canvas.drawLine(x - dp(10), y, x, y + dp(10), paint);
            paint.setStrokeCap(Paint.Cap.BUTT);
            paint.setStyle(Paint.Style.FILL);
        }

        private void drawScrollChevron(Canvas canvas, float x, float y, boolean up) {
            paint.setColor(Color.rgb(105, 105, 105));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(3));
            if (up) {
                canvas.drawLine(x - dp(6), y + dp(5), x, y - dp(5), paint);
                canvas.drawLine(x, y - dp(5), x + dp(6), y + dp(5), paint);
            } else {
                canvas.drawLine(x - dp(6), y - dp(5), x, y + dp(5), paint);
                canvas.drawLine(x, y + dp(5), x + dp(6), y - dp(5), paint);
            }
            paint.setStyle(Paint.Style.FILL);
        }

        private void textBold(Canvas canvas, String value, float x, float y, float size, int color) {
            paint.setColor(color);
            paint.setStyle(Paint.Style.FILL);
            paint.setTextSize(size);
            paint.setTypeface(Typeface.create("sans", Typeface.BOLD));
            canvas.drawText(value == null ? "" : value, x, y, paint);
            paint.setTypeface(Typeface.create("sans", Typeface.NORMAL));
        }

        private void addBackHit(float top) {
            hits.add(new Hit(new RectF(0, top, dp(90), top + dp(78)), true));
        }

        private void drawContentPanel(Canvas canvas, Template content, boolean overlay) {
            int right = Math.min(getWidth() - 20, 600);
            int top = 20;
            int bottom = getHeight() - 24;
            drawPanel(canvas, 20, top, right, bottom);
            if (content instanceof ListTemplate) {
                ListTemplate list = (ListTemplate) content;
                title(canvas, list.getTitle(), 44, 60);
                drawItems(canvas, list.getSingleList(), 44, 92, right - 28);
            } else if (content instanceof PaneTemplate) {
                PaneTemplate pane = (PaneTemplate) content;
                title(canvas, pane.getTitle(), 44, 60);
                drawPane(canvas, pane.getPane(), 44, 92, right - 28);
            } else if (content instanceof PlaceListNavigationTemplate) {
                PlaceListNavigationTemplate list = (PlaceListNavigationTemplate) content;
                title(canvas, list.getTitle(), 44, 60);
                drawItems(canvas, list.getItemList(), 44, 92, right - 28);
            } else {
                text(canvas, content == null ? "Map" : content.getClass().getSimpleName(),
                        44, 60, 22, TEXT);
            }
        }

        private int drawItems(Canvas canvas, @Nullable ItemList list, int x, int y, int width) {
            if (list == null) return y;
            for (Item item : list.getItems()) {
                if (!(item instanceof Row)) continue;
                Row row = (Row) item;
                int h = row.getTexts().isEmpty() ? 68 : 88;
                drawPanel(canvas, x, y, x + width, y + h - 8);
                text(canvas, textOf(row.getTitle()), x + 20, y + 30, 19,
                        row.isEnabled() ? TEXT : MUTED);
                int textY = y + 56;
                for (CarText subtext : row.getTexts()) {
                    text(canvas, textOf(subtext), x + 20, textY, 14, MUTED);
                    textY += 18;
                }
                addHit(x, y, x + width, y + h - 8, row.getOnClickDelegate());
                y += h;
            }
            return y;
        }

        private void drawItemsPanel(Canvas canvas, ItemList list) {
            drawPanel(canvas, 24, 20, Math.min(getWidth() - 24, 620), getHeight() - 28);
            drawItems(canvas, list, 48, 52, Math.min(getWidth() - 56, 570));
        }

        private void drawPane(Canvas canvas, Pane pane, int x, int y, int width) {
            if (pane == null) return;
            for (Row row : pane.getRows()) {
                int h = row.getTexts().isEmpty() ? 72 : 92;
                drawPanel(canvas, x, y, x + width, y + h - 10);
                text(canvas, textOf(row.getTitle()), x + 18, y + 30, 19, TEXT);
                int textY = y + 57;
                for (CarText subtext : row.getTexts()) {
                    text(canvas, textOf(subtext), x + 18, textY, 14, MUTED);
                    textY += 18;
                }
                addHit(x, y, x + width, y + h - 10, row.getOnClickDelegate());
                y += h;
            }
            drawActionList(canvas, pane.getActions(), x, y + 8);
        }

        private void drawActionList(Canvas canvas, List<Action> actions, int x, int y) {
            if (actions == null) return;
            int offset = 0;
            for (Action action : actions) {
                drawAction(canvas, action, x + offset, y, x + offset + 150, y + 52);
                offset += 162;
            }
        }

        private void drawActionStrip(Canvas canvas, androidx.car.app.model.ActionStrip strip) {
            if (strip == null) return;
            int x = 24;
            int y = getHeight() - 88;
            for (Action action : strip.getActions()) {
                int width = 150;
                drawAction(canvas, action, x, y, x + width, y + 56);
                x += width + 12;
            }
        }

        private void drawAction(Canvas canvas, @Nullable Action action, int left, int top,
                                int right, int bottom) {
            if (action == null) return;
            drawPanel(canvas, left, top, right, bottom);
            String label = textOf(action.getTitle());
            if (label.isEmpty()) label = actionType(action);
            text(canvas, label, left + 16, top + 34, 16, action.isEnabled() ? TEXT : MUTED);
            if (action.getOnClickDelegate() != null) {
                addHit(left, top, right, bottom, action.getOnClickDelegate());
            }
        }

        private void addHit(float left, float top, float right, float bottom,
                            @Nullable androidx.car.app.model.OnClickDelegate delegate) {
            if (delegate != null) hits.add(new Hit(new RectF(left, top, right, bottom), delegate));
        }

        private void addToggleHit(float left, float top, float right, float bottom,
                                  androidx.car.app.model.OnCheckedChangeDelegate delegate,
                                  boolean checked) {
            if (delegate != null) {
                hits.add(new Hit(new RectF(left, top, right, bottom), delegate, checked));
            }
        }

        private void title(Canvas canvas, @Nullable CarText title, int x, int y) {
            text(canvas, textOf(title), x, y, 25, TEXT);
        }

        private void drawPanel(Canvas canvas, float left, float top, float right, float bottom) {
            paint.setColor(PANEL);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(left, top, right, bottom, dp(16), dp(16), paint);
        }

        private void text(Canvas canvas, String value, float x, float y, float size, int color) {
            paint.setColor(color);
            paint.setStyle(Paint.Style.FILL);
            paint.setTextSize(size);
            paint.setTypeface(Typeface.create("sans", Typeface.NORMAL));
            canvas.drawText(value == null ? "" : value, x, y, paint);
        }

        private void drawPin(Canvas canvas, float x, float y, float size) {
            paint.setColor(Color.rgb(18, 115, 194));
            paint.setStyle(Paint.Style.FILL);
            Path pin = new Path();
            pin.moveTo(x, y + size);
            pin.cubicTo(x - size, y, x - size * .78f, y - size, x, y - size);
            pin.cubicTo(x + size * .78f, y - size, x + size, y, x, y + size);
            canvas.drawPath(pin, paint);
            paint.setColor(Color.rgb(255, 153, 0));
            canvas.drawCircle(x, y - dp(1), size * .48f, paint);
        }

        private void drawChevron(Canvas canvas, float x, float y) {
            paint.setColor(Color.rgb(160, 160, 160));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(4));
            paint.setStrokeCap(Paint.Cap.SQUARE);
            canvas.drawLine(x - dp(7), y - dp(10), x + dp(3), y, paint);
            canvas.drawLine(x + dp(3), y, x - dp(7), y + dp(10), paint);
            paint.setStrokeCap(Paint.Cap.BUTT);
            paint.setStyle(Paint.Style.FILL);
        }

        private String textOf(@Nullable CarText value) {
            return value == null || value.isEmpty() ? "" : String.valueOf(value.toCharSequence());
        }

        private String actionType(Action action) {
            switch (action.getType()) {
                case Action.TYPE_BACK: return "Back";
                case Action.TYPE_APP_ICON: return "App";
                case Action.TYPE_PAN: return "Pan";
                case Action.TYPE_COMPOSE_MESSAGE: return "Compose";
                default: return "Action";
            }
        }

        private static final class PathHelper {
            static void drawNavigationArrow(Canvas canvas, Paint paint, float x, float y,
                                            float size) {
                Path path = new Path();
                path.moveTo(x, y - size);
                path.lineTo(x + size * .75f, y + size);
                path.lineTo(x, y + size * .55f);
                path.lineTo(x - size * .75f, y + size);
                path.close();
                canvas.drawPath(path, paint);
            }

            static void drawStar(Canvas canvas, Paint paint, float x, float y, float size) {
                Path path = new Path();
                for (int i = 0; i < 10; i++) {
                    double angle = -Math.PI / 2 + i * Math.PI / 5;
                    float radius = i % 2 == 0 ? size : size * .42f;
                    float px = x + (float) Math.cos(angle) * radius;
                    float py = y + (float) Math.sin(angle) * radius;
                    if (i == 0) path.moveTo(px, py); else path.lineTo(px, py);
                }
                path.close();
                canvas.drawPath(path, paint);
            }

            static void drawFlag(Canvas canvas, Paint paint, float x, float y, float size) {
                Path path = new Path();
                path.moveTo(x, y);
                path.lineTo(x + size, y + size * .35f);
                path.lineTo(x, y + size * .7f);
                path.close();
                canvas.drawPath(path, paint);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                downX = lastX = event.getX();
                downY = lastY = event.getY();
                dragging = false;
                listDragging = false;
                pressedHit = findHit(event.getX(), event.getY());
                backPressed = pressedHit != null && pressedHit.back;
                if (pressedHit == null && wrapper != null
                        && wrapper.getTemplate() instanceof ListTemplate
                        && event.getY() >= contentTop() + dp(78)) {
                    listDragging = true;
                }
                if (pressedHit == null && mapMode) {
                    dragging = false;
                }
                velocityTracker = VelocityTracker.obtain();
                velocityTracker.addMovement(event);
                return true;
            }
            if (velocityTracker != null) {
                velocityTracker.addMovement(event);
            }
            if (action == MotionEvent.ACTION_MOVE) {
                float dx = event.getX() - lastX;
                float dy = event.getY() - lastY;
                if (Math.hypot(event.getX() - downX, event.getY() - downY) > dp(8)) {
                    dragging = true;
                }
                if (pressedHit == null && listDragging) {
                    listScrollOffset = Math.max(0, Math.min(listMaxScroll,
                            listScrollOffset - dy));
                    invalidate();
                } else if (pressedHit == null && mapMode) {
                    // SurfaceCallback follows GestureDetector's scroll convention: its
                    // distance is the map displacement, opposite the finger delta.
                    session.onMapScroll(-dx, -dy);
                }
                lastX = event.getX();
                lastY = event.getY();
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (velocityTracker != null) {
                    velocityTracker.computeCurrentVelocity(1000);
                }
                if (pressedHit == null && mapMode) {
                    if (dragging && velocityTracker != null) {
                        float vx = velocityTracker.getXVelocity();
                        float vy = velocityTracker.getYVelocity();
                        if (Math.hypot(vx, vy) > 100) {
                            session.onMapFling(vx, vy);
                        }
                    } else if (!dragging && action == MotionEvent.ACTION_UP) {
                        session.onMapClick(event.getX(), event.getY());
                    }
                } else if (!dragging && action == MotionEvent.ACTION_UP) {
                    Hit hit = pressedHit;
                    if (backPressed) {
                        session.onBackPressed();
                    } else if (hit != null && hit.bounds.contains(event.getX(), event.getY())) {
                        if (hit.delegate != null) {
                            hit.delegate.sendClick(new OnDoneCallback() {});
                        } else if (hit.toggle != null) {
                            hit.toggle.sendCheckedChange(!hit.checked, new OnDoneCallback() {});
                        }
                    }
                }
                if (velocityTracker != null) {
                    velocityTracker.recycle();
                    velocityTracker = null;
                }
                pressedHit = null;
                return true;
            }
            return true;
        }

        private Hit findHit(float x, float y) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit hit = hits.get(i);
                if (hit.bounds.contains(x, y)) return hit;
            }
            return null;
        }

        private static final class Hit {
            final RectF bounds;
            final androidx.car.app.model.OnClickDelegate delegate;
            final androidx.car.app.model.OnCheckedChangeDelegate toggle;
            final boolean checked;
            final boolean back;
            Hit(RectF bounds, androidx.car.app.model.OnClickDelegate delegate) {
                this.bounds = bounds;
                this.delegate = delegate;
                this.toggle = null;
                this.checked = false;
                this.back = false;
            }
            Hit(RectF bounds, androidx.car.app.model.OnCheckedChangeDelegate toggle,
                boolean checked) {
                this.bounds = bounds;
                this.delegate = null;
                this.toggle = toggle;
                this.checked = checked;
                this.back = false;
            }
            Hit(RectF bounds, boolean back) {
                this.bounds = bounds;
                this.delegate = null;
                this.toggle = null;
                this.checked = false;
                this.back = back;
            }
        }
    }
}
