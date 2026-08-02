package com.android.car.libraries.templates.host;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
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
        templateView = new TemplateCanvasView(context, session);
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
        private static final int PANEL = Color.rgb(31, 35, 42);
        private static final int PANEL_ALT = Color.rgb(40, 45, 54);
        private static final int TEXT = Color.rgb(244, 246, 250);
        private static final int MUTED = Color.rgb(170, 178, 190);
        private static final int ACCENT = Color.rgb(118, 183, 255);

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TemplatesHostService.RendererSession session;
        private final List<Hit> hits = new ArrayList<>();
        private TemplateWrapper wrapper;
        private boolean mapMode;

        TemplateCanvasView(Context context, TemplatesHostService.RendererSession session) {
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
            this.wrapper = wrapper;
            invalidate();
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
            drawPanel(canvas, 24, 20, Math.min(getWidth() - 24, 560), getHeight() - 28);
            title(canvas, template.getTitle(), 48, 62);
            drawItems(canvas, template.getItemList(), 48, 94, Math.min(getWidth() - 56, 510));
            drawActionStrip(canvas, template.getActionStrip());
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
            int left = 24;
            int bottom = getHeight() - 30;
            drawPanel(canvas, left, bottom - 126, Math.min(getWidth() - 48, 640), bottom);
            text(canvas, "Navigation", left + 24, bottom - 86, 24, TEXT);
            text(canvas, "Map surface supplied to the app", left + 24, bottom - 52, 16, MUTED);
            drawActionStrip(canvas, template.getActionStrip());
        }

        private void drawListTemplate(Canvas canvas, ListTemplate template) {
            drawPanel(canvas, 24, 20, Math.min(getWidth() - 24, 620), getHeight() - 28);
            title(canvas, template.getTitle(), 48, 62);
            if (template.getSingleList() != null) {
                drawItems(canvas, template.getSingleList(), 48, 94, Math.min(getWidth() - 56, 570));
            } else {
                int y = 94;
                for (SectionedItemList section : template.getSectionedLists()) {
                    text(canvas, textOf(section.getHeader()), 48, y, 15, ACCENT);
                    y += 30;
                    y = drawItems(canvas, section.getItemList(), 48, y, Math.min(getWidth() - 56, 570));
                }
            }
            drawAction(canvas, template.getHeaderAction(), 48, 28, 210, 72);
            drawActionStrip(canvas, template.getActionStrip());
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

        private void title(Canvas canvas, @Nullable CarText title, int x, int y) {
            text(canvas, textOf(title), x, y, 25, TEXT);
        }

        private void drawPanel(Canvas canvas, float left, float top, float right, float bottom) {
            paint.setColor(PANEL);
            canvas.drawRoundRect(left, top, right, bottom, 18, 18, paint);
        }

        private void text(Canvas canvas, String value, float x, float y, float size, int color) {
            paint.setColor(color);
            paint.setTextSize(size);
            paint.setTypeface(Typeface.create("sans", Typeface.NORMAL));
            canvas.drawText(value == null ? "" : value, x, y, paint);
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

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() != MotionEvent.ACTION_UP) return true;
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit hit = hits.get(i);
                if (hit.bounds.contains(event.getX(), event.getY())) {
                    hit.delegate.sendClick(new OnDoneCallback() {});
                    return true;
                }
            }
            return true;
        }

        private static final class Hit {
            final RectF bounds;
            final androidx.car.app.model.OnClickDelegate delegate;
            Hit(RectF bounds, androidx.car.app.model.OnClickDelegate delegate) {
                this.bounds = bounds;
                this.delegate = delegate;
            }
        }
    }
}
