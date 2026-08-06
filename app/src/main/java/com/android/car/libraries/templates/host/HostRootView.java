/*
 * Copyright 2026 Radio Sound, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.car.libraries.templates.host;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Path;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ScaleGestureDetector;
import android.view.TextureView;
import android.graphics.SurfaceTexture;
import android.util.Log;
import android.view.VelocityTracker;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.car.app.model.Action;
import androidx.car.app.model.Alert;
import androidx.car.app.model.CarText;
import androidx.car.app.model.Distance;
import androidx.car.app.model.GridItem;
import androidx.car.app.model.GridTemplate;
import androidx.car.app.model.Item;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.LongMessageTemplate;
import androidx.car.app.model.MessageTemplate;
import androidx.car.app.OnDoneCallback;
import androidx.car.app.model.Pane;
import androidx.car.app.model.PaneTemplate;
import androidx.car.app.model.PlaceListMapTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Section;
import androidx.car.app.model.SectionedItemList;
import androidx.car.app.model.SectionedItemTemplate;
import androidx.car.app.model.SearchTemplate;
import androidx.car.app.model.Tab;
import androidx.car.app.model.TabContents;
import androidx.car.app.model.TabTemplate;
import androidx.car.app.model.Template;
import androidx.car.app.model.TemplateWrapper;
import androidx.car.app.model.signin.SignInTemplate;
import androidx.car.app.serialization.Bundleable;
import androidx.car.app.serialization.BundlerException;
import androidx.car.app.media.model.MediaPlaybackTemplate;
import androidx.car.app.navigation.model.MapWithContentTemplate;
import androidx.car.app.navigation.model.MapTemplate;
import androidx.car.app.navigation.model.Maneuver;
import androidx.car.app.navigation.model.MessageInfo;
import androidx.car.app.navigation.model.NavigationTemplate;
import androidx.car.app.navigation.model.PlaceListNavigationTemplate;
import androidx.car.app.navigation.model.RoutePreviewNavigationTemplate;
import androidx.car.app.navigation.model.RoutingInfo;
import androidx.car.app.navigation.model.Step;
import androidx.car.app.navigation.model.TravelEstimate;

import java.util.Date;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Surface content used by the open templates host. */
final class HostRootView extends FrameLayout {
    final MapSurfaceView mapSurface;
    final TemplateCanvasView templateView;
    final int densityDpi;

    HostRootView(Context context, int densityDpi, TemplatesHostService.RendererSession session,
                 @Nullable Drawable appIcon) {
        super(context);
        this.densityDpi = densityDpi;
        setBackgroundColor(Color.TRANSPARENT);
        mapSurface = new MapSurfaceView(context, session);
        templateView = new TemplateCanvasView(context, session, densityDpi, appIcon);
        addView(templateView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    void render(TemplateWrapper wrapper) {
        templateView.render(wrapper);
        Template template = wrapper == null ? null : wrapper.getTemplate();
        boolean map = template instanceof MapTemplate
                || template instanceof MapWithContentTemplate
                || template instanceof NavigationTemplate
                || template instanceof PlaceListNavigationTemplate
                || template instanceof PlaceListMapTemplate
                || template instanceof RoutePreviewNavigationTemplate;
        // SurfaceView keeps a separately-composited buffer. INVISIBLE/GONE do
        // not reliably remove that buffer from the host window, so detach it
        // completely for normal templates and attach it only for map content.
        boolean attached = mapSurface.getParent() != null;
        if (map && !attached) {
            addView(mapSurface, 0,
                    new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            mapSurface.setVisibility(VISIBLE);
        } else if (!map && attached) {
            removeView(mapSurface);
        }
        setBackgroundColor(map ? Color.TRANSPARENT : TemplateCanvasView.BG);
        templateView.setMapMode(map);
        templateView.bringToFront();
    }

    void setWindowInsets(Insets insets, Insets stableInsets) {
        templateView.setWindowInsets(insets, stableInsets);
    }

    void showToast(CharSequence text) {
        templateView.showToast(text);
    }

    void showAlert(Alert alert) {
        templateView.showAlert(alert);
    }

    void dismissAlert(int alertId) {
        templateView.dismissAlert(alertId);
    }

    void destroy() {
        mapSurface.destroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (templateView.handleRotaryKey(event)) return true;
        return super.dispatchKeyEvent(event);
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

    static final class TemplateCanvasView extends TextureView {
        private static final int BG = Color.rgb(19, 19, 19);
        private static final int PANEL = Color.rgb(43, 43, 43);
        private static final int PANEL_ALT = Color.rgb(68, 68, 68);
        private static final int DIVIDER = Color.rgb(76, 76, 76);
        private static final int TEXT = Color.rgb(232, 232, 232);
        private static final int MUTED = Color.rgb(183, 183, 183);
        private static final int ACCENT = Color.rgb(118, 183, 255);
        private static final int ICON = Color.rgb(245, 245, 245);

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TemplatesHostService.RendererSession session;
        private final List<Hit> hits = new ArrayList<>();
        private final Map<Section<?>, List<Item>> sectionItems = new HashMap<>();
        private final Map<String, Boolean> toggleOverrides = new HashMap<>();
        private final Set<Section<?>> loadingSections = new HashSet<>();
        private TemplateWrapper wrapper;
        private final Drawable appIcon;
        private Alert alert;
        private String searchText = "";
        private String composingText = "";
        private boolean mapMode;
        private Insets windowInsets = Insets.NONE;
        private Insets stableInsets = Insets.NONE;
        private String toast;
        private static final long NAVIGATION_CONTROLS_TIMEOUT_MS = 10000L;
        private long navigationControlsVisibleUntil;
        private NavigationTemplate.NavigationInfo lastNavigationInfo;
        private TravelEstimate lastNavigationEstimate;
        private final Runnable hideNavigationControls = () -> {
            navigationControlsVisibleUntil = 0;
            invalidate();
        };
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
        private final ScaleGestureDetector scaleDetector;
        private int scaleDebugCount;
        private boolean scaled;
        private float listMaxScroll;
        private Surface textureSurface;
        private final Runnable stopInput;
        private final Runnable stopLocalInput;
            private final InputConnection localInputConnection = new BaseInputConnection(this, true) {
            @Override public boolean commitText(CharSequence text, int newCursorPosition) {
                commitLocalSearchText(text == null ? "" : text.toString());
                return true;
            }

            @Override public boolean setComposingText(CharSequence text, int newCursorPosition) {
                setComposingLocalSearchText(text == null ? "" : text.toString());
                return true;
            }

            @Override public boolean finishComposingText() {
                composingText = "";
                return true;
            }

            @Override public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                return deleteSearchText(beforeLength);
            }

            @Override public boolean performEditorAction(int actionCode) {
                submitSearchText();
                return true;
            }

            @Override public CharSequence getTextBeforeCursor(int length, int flags) {
                return getSearchText(length);
            }

            @Override public android.view.inputmethod.ExtractedText getExtractedText(
                    android.view.inputmethod.ExtractedTextRequest request, int flags) {
                android.view.inputmethod.ExtractedText result =
                        new android.view.inputmethod.ExtractedText();
                result.text = searchText;
                result.selectionStart = result.selectionEnd = searchText.length();
                return result;
            }
        };

        TemplateCanvasView(Context context, TemplatesHostService.RendererSession session,
                           int densityDpi, @Nullable Drawable appIcon) {
            super(context);
            this.session = session;
            this.appIcon = appIcon;
            this.scaleDetector = new ScaleGestureDetector(context,
                    new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                            boolean accepted = mapMode && pressedHit == null;
                            Log.i("CaramelTemplatesHost", "scaleBegin accepted=" + accepted
                                    + " mapMode=" + mapMode + " pressedHit=" + (pressedHit != null)
                                    + " focus=" + detector.getFocusX() + "," + detector.getFocusY());
                            return accepted;
                        }

                        @Override public boolean onScale(ScaleGestureDetector detector) {
                            if (!mapMode || pressedHit != null) {
                                Log.w("CaramelTemplatesHost", "scaleRejected mapMode=" + mapMode
                                        + " pressedHit=" + (pressedHit != null));
                                return false;
                            }
                            scaled = true;
                            dragging = true;
                            scaleDebugCount++;
                            if (scaleDebugCount == 1 || scaleDebugCount % 5 == 0) {
                                Log.i("CaramelTemplatesHost", "scale count=" + scaleDebugCount
                                        + " factor=" + detector.getScaleFactor()
                                        + " focus=" + detector.getFocusX() + "," + detector.getFocusY());
                            }
                            session.onMapScale(detector.getFocusX(), detector.getFocusY(),
                                    detector.getScaleFactor());
                            return true;
                        }
                    });
            this.stopInput = session::stopInput;
            this.stopLocalInput = this::stopLocalInput;
            // The hosted surface also supplies the local editor used by
            // SearchTemplate. It starts unfocused and only requests focus when
            // the search field is tapped.
            setFocusable(false);
            setFocusableInTouchMode(false);
            // SurfaceControlViewHost publishes a transparent window. TextureView
            // gives the renderer an opaque child buffer so the template does not
            // inherit the translucent CarAppActivity backdrop.
            setOpaque(true);
            paint.setTypeface(Typeface.create("sans", Typeface.NORMAL));
            setSurfaceTextureListener(new SurfaceTextureListener() {
                @Override public void onSurfaceTextureAvailable(SurfaceTexture surface,
                                                                  int width, int height) {
                    textureSurface = new Surface(surface);
                    post(TemplateCanvasView.this::redrawSurface);
                }

                @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface,
                                                                   int width, int height) {
                    post(TemplateCanvasView.this::redrawSurface);
                }

                @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                    if (textureSurface != null) {
                        textureSurface.release();
                        textureSurface = null;
                    }
                    return true;
                }

                @Override public void onSurfaceTextureUpdated(SurfaceTexture surface) { }
            });
        }

        @Override public void invalidate() {
            super.invalidate();
            post(this::redrawSurface);
        }

        private void redrawSurface() {
            if (textureSurface == null || !textureSurface.isValid()) return;
            Canvas canvas = null;
            try {
                canvas = textureSurface.lockCanvas(null);
                if (canvas != null) drawFrame(canvas);
            } catch (RuntimeException ignored) {
                // The SurfaceTexture can disappear between the validity check
                // and lockCanvas during an activity transition.
            } finally {
                if (canvas != null) textureSurface.unlockCanvasAndPost(canvas);
            }
        }

        void setMapMode(boolean mapMode) {
            this.mapMode = mapMode;
            setOpaque(!mapMode);
            invalidate();
        }

        void render(TemplateWrapper wrapper) {
            Template incomingTemplate = wrapper == null ? null : wrapper.getTemplate();
            if (incomingTemplate instanceof NavigationTemplate) {
                NavigationTemplate navigation = (NavigationTemplate) incomingTemplate;
                if (navigation.getNavigationInfo() != null) {
                    lastNavigationInfo = navigation.getNavigationInfo();
                }
                if (navigation.getDestinationTravelEstimate() != null) {
                    lastNavigationEstimate = navigation.getDestinationTravelEstimate();
                }
            } else {
                lastNavigationInfo = null;
                lastNavigationEstimate = null;
                navigationControlsVisibleUntil = 0;
            }
            if (this.wrapper != wrapper) {
                listScrollOffset = 0;
                if (wrapper != null && wrapper.getTemplate() instanceof SearchTemplate) {
                    searchText = ((SearchTemplate) wrapper.getTemplate()).getInitialSearchText();
                    if (searchText == null) searchText = "";
                }
            }
            this.wrapper = wrapper;
            invalidate();
            boolean searchTemplate = wrapper != null
                    && wrapper.getTemplate() instanceof SearchTemplate;
            setFocusable(true);
            setFocusableInTouchMode(true);
            if (!searchTemplate) {
                post(this::requestFocus);
            }
            removeCallbacks(stopInput);
            removeCallbacks(stopLocalInput);
            if (searchTemplate
                    && ((SearchTemplate) wrapper.getTemplate()).isShowKeyboardByDefault()) {
                postDelayed(this::startLocalInput, 120);
            } else if (!searchTemplate) {
                // A renderer can deliver an old template immediately before
                // the new SearchTemplate during a task transition. Delay the
                // hide long enough for the current template to cancel it.
                postDelayed(stopInput, 180);
                postDelayed(stopLocalInput, 180);
            }
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

        void showAlert(Alert alert) {
            this.alert = alert;
            invalidate();
        }

        void dismissAlert(int alertId) {
            if (alert != null && alert.getId() == alertId) {
                alert = null;
                invalidate();
            }
        }

        String getSearchText(int length) {
            if (length <= 0 || length >= searchText.length()) return searchText;
            return searchText.substring(Math.max(0, searchText.length() - length));
        }

        boolean deleteSearchText(int beforeLength) {
            if (beforeLength <= 0 || searchText.isEmpty()) return true;
            int start = Math.max(0, searchText.length() - beforeLength);
            replaceSearchText(searchText.substring(0, start));
            return true;
        }

        void replaceSearchText(String value) {
            searchText = value == null ? "" : value;
            Template template = wrapper == null ? null : wrapper.getTemplate();
            if (template instanceof SearchTemplate
                    && ((SearchTemplate) template).getSearchCallbackDelegate() != null) {
                try {
                    ((SearchTemplate) template).getSearchCallbackDelegate()
                            .sendSearchTextChanged(searchText, new OnDoneCallback() {});
                } catch (RuntimeException ignored) {
                    // The remote app may be stopping; the next invalidate will refresh it.
                }
            }
            invalidate();
        }

        private void setComposingLocalSearchText(String value) {
            String base = searchText;
            if (!composingText.isEmpty() && base.endsWith(composingText)) {
                base = base.substring(0, base.length() - composingText.length());
            }
            composingText = value;
            replaceSearchText(base + value);
        }

        private void commitLocalSearchText(String value) {
            String base = searchText;
            if (!composingText.isEmpty() && base.endsWith(composingText)) {
                base = base.substring(0, base.length() - composingText.length());
            }
            composingText = "";
            replaceSearchText(base + value);
        }

        void submitSearchText() {
            Template template = wrapper == null ? null : wrapper.getTemplate();
            if (template instanceof SearchTemplate
                    && ((SearchTemplate) template).getSearchCallbackDelegate() != null) {
                try {
                    ((SearchTemplate) template).getSearchCallbackDelegate()
                            .sendSearchSubmitted(searchText, new OnDoneCallback() {});
                } catch (RuntimeException ignored) {
                    // The remote app may be stopping; the next invalidate will refresh it.
                }
            }
        }

        private void startLocalInput() {
            Template template = wrapper == null ? null : wrapper.getTemplate();
            if (!(template instanceof SearchTemplate)) return;
            requestFocus();
            InputMethodManager manager = (InputMethodManager) getContext()
                    .getSystemService(Context.INPUT_METHOD_SERVICE);
            if (manager != null) {
                manager.restartInput(this);
                postDelayed(() -> {
                    if (hasFocus() && isAttachedToWindow()) {
                        manager.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
                    }
                }, 180);
            }
        }

        private void stopLocalInput() {
            InputMethodManager manager = (InputMethodManager) getContext()
                    .getSystemService(Context.INPUT_METHOD_SERVICE);
            if (manager != null && getWindowToken() != null) {
                manager.hideSoftInputFromWindow(getWindowToken(), 0);
            }
        }

        @Override public boolean onCheckIsTextEditor() {
            return wrapper != null && wrapper.getTemplate() instanceof SearchTemplate;
        }

        @Override public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
            if (!onCheckIsTextEditor()) return null;
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT;
            outAttrs.imeOptions = EditorInfo.IME_ACTION_SEARCH;
            outAttrs.hintText = "Search places";
            outAttrs.initialSelStart = outAttrs.initialSelEnd = searchText.length();
            return localInputConnection;
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

        private void drawFrame(Canvas canvas) {
            hits.clear();
            Template template = wrapper == null ? null : wrapper.getTemplate();
            if (!mapMode) {
                canvas.drawColor(BG);
            } else {
                // Leave the map surface visible and only paint the overlay/panel.
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
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
            } else if (template instanceof GridTemplate) {
                drawGridTemplate(canvas, (GridTemplate) template);
            } else if (template instanceof LongMessageTemplate) {
                drawLongMessageTemplate(canvas, (LongMessageTemplate) template);
            } else if (template instanceof SignInTemplate) {
                drawSignInTemplate(canvas, (SignInTemplate) template);
            } else if (template instanceof TabTemplate) {
                drawTabTemplate(canvas, (TabTemplate) template);
            } else if (template instanceof SectionedItemTemplate) {
                drawSectionedItemTemplate(canvas, (SectionedItemTemplate) template);
            } else if (template instanceof PlaceListMapTemplate) {
                drawPlaceListMapTemplate(canvas, (PlaceListMapTemplate) template);
            } else if (template instanceof RoutePreviewNavigationTemplate) {
                drawRoutePreviewTemplate(canvas, (RoutePreviewNavigationTemplate) template);
            } else if (template instanceof MediaPlaybackTemplate) {
                drawMediaPlaybackTemplate(canvas, (MediaPlaybackTemplate) template);
            } else {
                text(canvas, template.getClass().getSimpleName(), 32, 58, 22, TEXT);
                text(canvas, "This Caramel Vanilla host does not render this template yet.",
                        32, 94, 16, MUTED);
            }
            if (alert != null) {
                drawAlert(canvas, alert);
            }
        }

        private void drawAlert(Canvas canvas, Alert value) {
            float left = dp(42);
            float top = contentTop() + dp(18);
            float right = getWidth() - dp(42);
            float bottom = top + dp(155);
            paint.setColor(Color.rgb(44, 47, 52));
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(left, top, right, bottom, dp(18), dp(18), paint);
            addAlertHit(left, top, right, bottom, value.getCallbackDelegate(), value.getId(), null);
            textBold(canvas, textOf(value.getTitle()), left + dp(26), top + dp(43), dp(22), TEXT);
            drawWrappedText(canvas, textOf(value.getSubtitle()), left + dp(26), top + dp(76),
                    right - left - dp(52), dp(16), MUTED);
            float actionLeft = left + dp(24);
            if (value.getActions() != null) {
                for (Action action : value.getActions()) {
                    drawAction(canvas, action, actionLeft, bottom - dp(58),
                            actionLeft + dp(150), bottom - dp(10));
                    addAlertHit(actionLeft, bottom - dp(58), actionLeft + dp(150),
                            bottom - dp(10), value.getCallbackDelegate(), value.getId(),
                            action.getOnClickDelegate());
                    actionLeft += dp(162);
                }
            }
        }

        private void drawMapWithContent(Canvas canvas, MapWithContentTemplate template) {
            Template content = template.getContentTemplate();
            if (content instanceof PaneTemplate) {
                drawMapPaneContent(canvas, (PaneTemplate) content);
            } else {
                drawContentPanel(canvas, content, true);
            }
            // The normal action strip already supplies the correct icon for its
            // action. Forcing every action to the settings glyph made route
            // previews look like a row of identical gears.
            drawMapActionStrip(canvas, template.getActionStrip(), panelTop() + dp(42), false);
        }

        private void drawMapPaneContent(Canvas canvas, PaneTemplate template) {
            float left = dp(18);
            float top = panelTop() + dp(6);
            float right = Math.min(getWidth() - dp(24), left + dp(620));
            Pane pane = template.getPane();
            float bodyBottom = top + dp(84);
            if (pane != null) {
                for (Row row : pane.getRows()) {
                    bodyBottom += routeRowHeight(row);
                }
                if (pane.getActions() != null && !pane.getActions().isEmpty()) {
                    bodyBottom += dp(8) + dp(54);
                }
            }
            float bottom = Math.min(panelBottom(),
                    Math.max(bodyBottom + dp(18), top + dp(170)));
            drawPanel(canvas, left, top, right, bottom);

            androidx.car.app.model.Header header = template.getHeader();
            drawHeader(canvas,
                    header == null ? template.getTitle() : header.getTitle(),
                    header == null ? template.getHeaderAction() : header.getStartHeaderAction(),
                    left + dp(24), top + dp(6));
            paint.setColor(DIVIDER);
            canvas.drawRect(left + dp(18), top + dp(68), right - dp(18), top + dp(69), paint);
            drawRoutePane(canvas, pane, left + dp(24), top + dp(84),
                    right - left - dp(48));
        }

        private void drawPlaceListNavigation(Canvas canvas, PlaceListNavigationTemplate template) {
            float left = dp(12);
            float top = panelTop();
            float right = Math.min(getWidth() - dp(12), left + dp(520));
            float bottom = panelBottom();
            drawPanel(canvas, left, top, right, bottom);
            androidx.car.app.model.Header header = template.getHeader();
            CarText title = header == null ? template.getTitle() : header.getTitle();
            Action headerAction = header == null
                    ? template.getHeaderAction() : header.getStartHeaderAction();
            if (headerAction != null && headerAction.getType() == Action.TYPE_BACK) {
                drawHeader(canvas, title, headerAction, left + dp(12), top + dp(10));
                paint.setColor(DIVIDER);
                canvas.drawRect(left, top + dp(84) - 1, right, top + dp(84), paint);
            } else {
                drawAppHeader(canvas, title, left, top, right);
            }
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
            NavigationTemplate.NavigationInfo info = template.getNavigationInfo();
            if (info == null) info = lastNavigationInfo;
            if (info instanceof RoutingInfo) {
                drawRoutingInfo(canvas, (RoutingInfo) info);
            } else if (info instanceof MessageInfo) {
                drawNavigationMessage(canvas, (MessageInfo) info);
            } else {
                drawNavigationWaiting(canvas);
            }
            TravelEstimate estimate = template.getDestinationTravelEstimate();
            if (estimate == null) estimate = lastNavigationEstimate;
            drawNavigationTripEstimate(canvas, estimate);
            drawNavigationSpeed(canvas);
            if (navigationControlsVisible()) {
                drawNavigationActionStrip(canvas, template.getActionStrip());
                drawNavigationMapActionStack(canvas, template.getMapActionStrip());
            }
            drawToast(canvas);
        }

        private void drawNavigationWaiting(Canvas canvas) {
            float left = dp(18);
            float top = contentTop() + dp(10);
            float right = Math.min(getWidth() - dp(18), left + dp(420));
            float bottom = top + dp(88);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(25, 30, 34));
            canvas.drawRoundRect(left, top, right, bottom, dp(12), dp(12), paint);
            text(canvas, "Navigation", left + dp(18), top + dp(35), dp(24), TEXT);
            text(canvas, "Waiting for route guidance…", left + dp(18), top + dp(65),
                    dp(19), MUTED);
        }

        private void drawNavigationActionStrip(Canvas canvas,
                                               @Nullable androidx.car.app.model.ActionStrip strip) {
            if (strip == null || strip.getActions().isEmpty()) return;
            List<Action> actions = strip.getActions();
            int first = Math.max(0, actions.size() - 3);
            float right = getWidth() - dp(18);
            float centerY = contentTop() + dp(50);
            for (int i = actions.size() - 1; i >= first; i--) {
                Action action = actions.get(i);
                String label = textOf(action.getTitle());
                if (!label.isEmpty()) {
                    float left = right - dp(150);
                    paint.setColor(Color.BLACK);
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawRoundRect(left, centerY - dp(36), right,
                            centerY + dp(36), dp(36), dp(36), paint);
                    centerText(canvas, label, (left + right) / 2, centerY + dp(8), dp(20),
                            TEXT, right - left - dp(20));
                    addHit(left, centerY - dp(36), right, centerY + dp(36),
                            action.getOnClickDelegate());
                    right = left - dp(12);
                } else {
                    float centerX = right - dp(40);
                    paint.setColor(Color.BLACK);
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(centerX, centerY, dp(40), paint);
                    drawNavigationActionIcon(canvas, action, centerX, centerY,
                            i == actions.size() - 2);
                    addHit(centerX - dp(40), centerY - dp(40), centerX + dp(40),
                            centerY + dp(40), action.getOnClickDelegate());
                    right = centerX - dp(58);
                }
            }
        }

        private void drawNavigationActionIcon(Canvas canvas, Action action, float x, float y,
                                               boolean settingsSlot) {
            String label = textOf(action.getTitle()).toLowerCase(Locale.US);
            if (settingsSlot || label.contains("setting")) {
                drawActionIcon(canvas, action, x, y, true);
                return;
            }
            paint.setColor(ICON);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(3));
            PathHelper.drawNavigationArrow(canvas, paint, x, y, dp(16));
            paint.setStyle(Paint.Style.FILL);
        }

        private void drawNavigationMapActionStack(Canvas canvas,
                                                  @Nullable androidx.car.app.model.ActionStrip strip) {
            if (strip == null || strip.getActions().isEmpty()) return;
            List<Action> actions = strip.getActions();
            int first = Math.max(0, actions.size() - 3);
            float x = getWidth() - dp(53);
            float top = panelTop();
            float bottom = panelBottom();
            float center = top + (bottom - top) * .64f;
            int visibleIndex = 0;
            for (int i = first; i < actions.size(); i++) {
                Action action = actions.get(i);
                float y = center + visibleIndex * dp(92);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.BLACK);
                canvas.drawCircle(x, y, dp(40), paint);
                drawMapStackIcon(canvas, action, visibleIndex, x, y);
                addHit(x - dp(40), y - dp(40), x + dp(40), y + dp(40),
                        action.getOnClickDelegate());
                visibleIndex++;
            }
        }

        private void drawRoutingInfo(Canvas canvas, RoutingInfo info) {
            float left = dp(18);
            float top = contentTop() + dp(10);
            float right = Math.min(getWidth() - dp(18), left + dp(420));
            float mainHeight = dp(154);
            float nextHeight = dp(58);
            float bottom = top + mainHeight + nextHeight;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(164, 180, 192));
            canvas.drawRoundRect(left, top, right, bottom, dp(14), dp(14), paint);
            if (info.isLoading()) {
                text(canvas, "Calculating route…", left + dp(18), top + dp(54), dp(22),
                        Color.WHITE);
                return;
            }

            paint.setColor(Color.rgb(104, 121, 134));
            canvas.drawRect(left, top + mainHeight, right, bottom - dp(14), paint);
            Step current = info.getCurrentStep();
            if (current != null) {
                Maneuver maneuver = current.getManeuver();
                if (maneuver != null) {
                    drawCarIcon(canvas, maneuver.getIcon(), left + dp(58), top + dp(70),
                            dp(60));
                }
                text(canvas, formatDistance(info.getCurrentDistance()), left + dp(96),
                        top + dp(72), dp(37), Color.WHITE);
                String road = textOf(current.getRoad());
                if (road.isEmpty()) road = textOf(current.getCue());
                text(canvas, road, left + dp(16), top + dp(122), dp(24), Color.WHITE);
            }

            Step next = info.getNextStep();
            if (next != null) {
                Maneuver maneuver = next.getManeuver();
                if (maneuver != null) {
                    drawCarIcon(canvas, maneuver.getIcon(), left + dp(38),
                            top + mainHeight + dp(29), dp(36));
                }
                String nextText = textOf(next.getCue());
                if (nextText.isEmpty()) nextText = textOf(next.getRoad());
                text(canvas, nextText, left + dp(70), top + mainHeight + dp(37),
                        dp(22), Color.WHITE);
            }
        }

        private void drawNavigationMessage(Canvas canvas, MessageInfo info) {
            float left = dp(18);
            float top = contentTop() + dp(10);
            float right = Math.min(getWidth() - dp(18), left + dp(420));
            float bottom = top + dp(126);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(164, 180, 192));
            canvas.drawRoundRect(left, top, right, bottom, dp(14), dp(14), paint);
            drawCarIcon(canvas, info.getImage(), left + dp(52), top + dp(52), dp(48));
            text(canvas, textOf(info.getTitle()), left + dp(90), top + dp(54), dp(26),
                    Color.WHITE);
            text(canvas, textOf(info.getText()), left + dp(18), top + dp(98), dp(20),
                    Color.WHITE);
        }

        private void drawNavigationTripEstimate(Canvas canvas, @Nullable TravelEstimate estimate) {
            if (estimate == null) return;
            String arrival = "";
            if (estimate.getArrivalTimeAtDestination() != null) {
                arrival = android.text.format.DateFormat.getTimeFormat(getContext()).format(
                        new Date(estimate.getArrivalTimeAtDestination().getTimeSinceEpochMillis()));
            }
            String remainingTime = formatRemainingTime(estimate.getRemainingTimeSeconds());
            String remainingDistance = formatDistance(estimate.getRemainingDistance());
            String summary = remainingTime;
            if (!remainingTime.isEmpty() && !remainingDistance.isEmpty()) {
                summary += " · ";
            }
            summary += remainingDistance;
            if (arrival.isEmpty() && summary.isEmpty()) return;

            float left = dp(18);
            float bottom = contentBottom() - dp(30);
            float right = Math.min(getWidth() - dp(18), left + dp(420));
            float top = bottom - dp(82);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(25, 30, 34));
            canvas.drawRoundRect(left, top, right, bottom, dp(10), dp(10), paint);
            text(canvas, arrival, left + dp(16), top + dp(31), dp(20), TEXT);
            text(canvas, summary, left + dp(16), top + dp(61), dp(20), TEXT);
        }

        private void drawNavigationSpeed(Canvas canvas) {
            float right = getWidth() - dp(18);
            float left = right - dp(62);
            float top = contentTop() + dp(18);
            float bottom = top + dp(62);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            canvas.drawRoundRect(left, top, right, bottom, dp(8), dp(8), paint);
            centerText(canvas, "0", (left + right) / 2, top + dp(33), dp(24),
                    Color.DKGRAY, right - left - dp(8));
            centerText(canvas, "MPH", (left + right) / 2, top + dp(51), dp(11),
                    Color.GRAY, right - left - dp(8));
        }

        private boolean navigationControlsVisible() {
            return SystemClock.uptimeMillis() < navigationControlsVisibleUntil;
        }

        private void showNavigationControls() {
            Template template = wrapper == null ? null : wrapper.getTemplate();
            if (!(template instanceof NavigationTemplate)) return;
            navigationControlsVisibleUntil =
                    SystemClock.uptimeMillis() + NAVIGATION_CONTROLS_TIMEOUT_MS;
            removeCallbacks(hideNavigationControls);
            postDelayed(hideNavigationControls, NAVIGATION_CONTROLS_TIMEOUT_MS);
            invalidate();
        }

        private String formatDistance(@Nullable Distance distance) {
            if (distance == null) return "";
            double value = distance.getDisplayDistance();
            switch (distance.getDisplayUnit()) {
                case Distance.UNIT_FEET:
                    return String.format(Locale.US, "%.0f ft", value);
                case Distance.UNIT_YARDS:
                    return String.format(Locale.US, "%.0f yd", value);
                case Distance.UNIT_MILES:
                case Distance.UNIT_MILES_P1:
                    return String.format(Locale.US, "%.1f mi", value);
                case Distance.UNIT_KILOMETERS:
                case Distance.UNIT_KILOMETERS_P1:
                    return String.format(Locale.US, "%.1f km", value);
                case Distance.UNIT_METERS:
                default:
                    return String.format(Locale.US, "%.0f m", value);
            }
        }

        private String formatRemainingTime(long seconds) {
            if (seconds < 0) return "";
            long minutes = Math.max(1, Math.round(seconds / 60.0));
            if (minutes < 60) return minutes + " min";
            long hours = minutes / 60;
            long remainder = minutes % 60;
            return remainder == 0 ? hours + " hr" : hours + " hr " + remainder + " min";
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
                contentEnd = drawSettingsRows(canvas, template.getSingleList(),
                        toolbarTop + dp(80) - listScrollOffset);
            } else {
                contentEnd = listTop;
                for (SectionedItemList section : template.getSectionedLists()) {
                    drawSectionHeader(canvas, section.getHeader(), contentEnd);
                    contentEnd = drawSettingsRows(canvas, section.getItemList(), contentEnd + dp(17));
                }
            }
            canvas.restore();
            if (template.getSingleList() != null) {
                drawScrollChevron(canvas, dp(42), toolbarTop + dp(120), true);
            }
            drawScrollChevron(canvas, dp(42), listBottom - dp(42), false);
            listMaxScroll = Math.max(0, contentEnd + listScrollOffset - listBottom);
            drawToast(canvas);
        }

        private void drawToolbar(Canvas canvas, ListTemplate template, float top) {
            boolean back = template.getHeaderAction() != null
                    && template.getHeaderAction().getType() == Action.TYPE_BACK;
            if (back) {
                drawBackArrow(canvas, dp(53), top + dp(40));
                addBackHit(top);
                text(canvas, "Settings", dp(88), top + dp(51), 24, TEXT);
            } else {
                text(canvas, textOf(template.getTitle()), 24, top + dp(51), 24, TEXT);
            }
        }

        private void drawSectionHeader(Canvas canvas, @Nullable CarText header, float baseline) {
            textBold(canvas, textOf(header), dp(88), baseline, dp(24), TEXT);
            drawScrollChevron(canvas, dp(42), baseline - dp(2), true);
        }

        private float drawSettingsRows(Canvas canvas, @Nullable ItemList list, float y) {
            if (list == null) return y;
            boolean first = true;
            int rowCount = 0;
            for (Item item : list.getItems()) {
                if (item instanceof Row) rowCount++;
            }
            int rowIndex = 0;
            for (Item item : list.getItems()) {
                if (!(item instanceof Row)) continue;
                Row row = (Row) item;
                float rowTop = y;
                boolean hasText = !row.getTexts().isEmpty();
                int textLength = 0;
                for (CarText subtext : row.getTexts()) {
                    textLength += textOf(subtext).length();
                }
                float rowHeight = !hasText ? dp(62)
                        : dp(textLength > 90 ? 122 : 96);
                // Toggle rows are rendered as controls rather than rotary-focus
                // selections. The stock host does not outline the first switch
                // when the list is initially shown; ordinary clickable rows in
                // the conformance list retain the stock initial focus outline.
                if (first && row.getToggle() == null) {
                    drawSelectionPanel(canvas, 54, rowTop, getWidth() - 54, rowTop + rowHeight);
                }
                first = false;
                text(canvas, textOf(row.getTitle()), dp(88), rowTop + dp(40), 20,
                        row.isEnabled() ? TEXT : MUTED);
                    float textY = rowTop + dp(69);
                for (CarText subtext : row.getTexts()) {
                    textY = drawWrappedText(canvas, textOf(subtext), dp(88), textY,
                            // Leave the same right-side breathing room as the
                            // stock settings host so long descriptions wrap
                            // before the trailing words instead of running
                            // underneath the switch column.
                            getWidth() - dp(430), dp(21), MUTED);
                }
                boolean rowToggleChecked = false;
                if (row.getToggle() != null) {
                    String toggleKey = textOf(row.getTitle());
                    boolean checked = row.getToggle().isChecked();
                    Boolean override = toggleOverrides.get(toggleKey);
                    if (override != null) {
                        if (override == checked) {
                            toggleOverrides.remove(toggleKey);
                        } else {
                            checked = override;
                        }
                    }
                    drawToggle(canvas, getWidth() - dp(115),
                            rowTop + (hasText ? dp(47) : dp(30)), checked);
                    rowToggleChecked = checked;
                }
                if (row.getOnClickDelegate() != null) {
                    addHit(dp(70), rowTop, getWidth() - dp(70), rowTop + rowHeight,
                            row.getOnClickDelegate());
                }
                // Put the checked-change delegate on top of a row click
                // delegate when both are present. Toggle rows must toggle
                // consistently no matter whether the user taps the switch
                // itself or the row body.
                if (row.getToggle() != null
                        && row.getToggle().getOnCheckedChangeDelegate() != null) {
                    addToggleHit(dp(70), rowTop, getWidth() - dp(70), rowTop + rowHeight,
                            row.getToggle().getOnCheckedChangeDelegate(),
                            rowToggleChecked, textOf(row.getTitle()));
                }
                if (rowIndex + 1 < rowCount) {
                    paint.setColor(DIVIDER);
                    canvas.drawRect(dp(88), rowTop + rowHeight - 1,
                            getWidth() - dp(88), rowTop + rowHeight, paint);
                }
                rowIndex++;
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
            float top = contentTop();
            drawHeader(canvas, template.getTitle(), template.getHeaderAction(), 24, top + 12);
            drawPane(canvas, template.getPane(), 66, (int) top + 82, getWidth() - 132);
            drawActionStrip(canvas, template.getActionStrip());
        }

        private void drawMessageTemplate(Canvas canvas, MessageTemplate template) {
            float top = contentTop();
            drawHeader(canvas, template.getTitle(), template.getHeaderAction(), 24, top + 12);
            centerText(canvas, textOf(template.getMessage()), getWidth() / 2f,
                    top + 230, 20, TEXT, getWidth() - 160);
            drawCenteredActions(canvas, template.getActions(), contentBottom() - 78);
            drawActionStrip(canvas, template.getActionStrip());
        }

        private void drawSearchTemplate(Canvas canvas, SearchTemplate template) {
            float top = contentTop() + 10;
            drawSearchField(canvas, template.getSearchHint(), top);
            float listTop = top + 50;
            float listBottom = contentBottom();
            canvas.save();
            canvas.clipRect(0, listTop, getWidth(), listBottom);
            float contentEnd = drawSearchItems(canvas, template.getItemList(), 66, listTop,
                    getWidth() - 132, listScrollOffset);
            canvas.restore();
            listMaxScroll = Math.max(0, contentEnd - listBottom);
            drawScrollChevron(canvas, dp(42), listTop + dp(40), true);
            drawScrollChevron(canvas, dp(42), listBottom - dp(40), false);
            drawAction(canvas, template.getHeaderAction(), 24, contentTop() + 10, 174,
                    contentTop() + 62);
            drawActionStrip(canvas, template.getActionStrip());
        }

        private void drawGridTemplate(Canvas canvas, GridTemplate template) {
            float top = contentTop();
            drawHeader(canvas, template.getTitle(), template.getHeaderAction(), 24, top + 12);
            ItemList list = template.getSingleList();
            if (list == null) {
                text(canvas, template.isLoading() ? "Loading…" : "No items", 48,
                        top + 120, 20, MUTED);
            } else {
                int columns = template.getItemSize() == GridTemplate.ITEM_SIZE_SMALL ? 6 : 5;
                float cellWidth = (getWidth() - 108) / (float) columns;
                float x = 54;
                float listTop = top + 60;
                float viewportBottom = contentBottom() - dp(45);
                float y = listTop - listScrollOffset;
                int column = 0;
                int itemIndex = 0;
                int rowCount = 0;
                int canvasSave = canvas.save();
                canvas.clipRect(0, listTop, getWidth(), viewportBottom);
                for (Item item : list.getItems()) {
                    if (!(item instanceof GridItem)) continue;
                    GridItem gridItem = (GridItem) item;
                    float cellLeft = x + column * cellWidth;
                    drawGridItem(canvas, gridItem, cellLeft, y, cellWidth - 12, itemIndex == 0);
                    itemIndex++;
                    column++;
                    if (column == columns) {
                        column = 0;
                        y += 168;
                        rowCount++;
                    }
                }
                if (column != 0) rowCount++;
                float contentEnd = listTop + rowCount * 168;
                listMaxScroll = Math.max(0, contentEnd - viewportBottom);
                canvas.restoreToCount(canvasSave);
                drawScrollChevron(canvas, dp(42), listTop + dp(40), true);
                drawScrollChevron(canvas, dp(42), contentBottom() - dp(40), false);
            }
            drawCenteredActions(canvas, template.getActions(), contentBottom() - 78);
            drawActionStrip(canvas, template.getActionStrip());
        }

        private void drawGridItem(Canvas canvas, GridItem item, float left, float top,
                                  float width, boolean selected) {
            float height = 166;
            if (selected) drawSelectionPanel(canvas, left, top, left + width, top + height);
            float imageSize = 92;
            float imageX = left + width / 2;
            float imageY = top + 58;
            if (item.getImage() != null) {
                drawCarIcon(canvas, item.getImage(), imageX, imageY, imageSize);
            } else {
                paint.setColor(Color.rgb(86, 92, 100));
                canvas.drawCircle(imageX, imageY, imageSize / 2, paint);
            }
            String title = textOf(item.getTitle());
            if (!title.isEmpty()) {
                centerText(canvas, title, imageX, top + 126, 18, TEXT, width - 16);
            }
            String subtitle = textOf(item.getText());
            if (!subtitle.isEmpty()) {
                centerText(canvas, subtitle, imageX, top + 150, 14, MUTED, width - 16);
            }
            addHit(left, top, left + width, top + height, item.getOnClickDelegate());
        }

        private void drawLongMessageTemplate(Canvas canvas, LongMessageTemplate template) {
            float top = contentTop();
            drawHeader(canvas, template.getTitle(), template.getHeaderAction(), 24, top + 12);
            drawWrappedText(canvas, textOf(template.getMessage()), 66, top + 215,
                    getWidth() - 132, 20, TEXT);
            drawCenteredActions(canvas, template.getActions(), contentBottom() - 78);
            drawActionStrip(canvas, template.getActionStrip());
        }

        private void drawSignInTemplate(Canvas canvas, SignInTemplate template) {
            float top = contentTop();
            drawHeader(canvas, template.getTitle(), template.getHeaderAction(), 24, top + 12);
            centerText(canvas, textOf(template.getInstructions()), getWidth() / 2f,
                    top + 220, 21, TEXT, getWidth() - 160);
            List<Action> actions = template.getActions();
            if ((actions == null || actions.isEmpty())
                    && template.getSignInMethod() instanceof androidx.car.app.model.signin.ProviderSignInMethod) {
                actions = Collections.singletonList(
                        ((androidx.car.app.model.signin.ProviderSignInMethod)
                                template.getSignInMethod()).getAction());
            }
            drawCompactCenteredAction(canvas, actions, top + 242);
            centerText(canvas, textOf(template.getAdditionalText()), getWidth() / 2f,
                    top + 320, 17, MUTED, getWidth() - 160);
            drawActionStrip(canvas, template.getActionStrip());
        }

        private void drawTabTemplate(Canvas canvas, TabTemplate template) {
            List<Tab> tabs = template.getTabs();
            float tabWidth = tabs == null || tabs.isEmpty() ? getWidth() : getWidth() / (float) tabs.size();
            float top = contentTop();
            if (tabs != null) {
                for (int i = 0; i < tabs.size(); i++) {
                    Tab tab = tabs.get(i);
                    float left = i * tabWidth;
                    boolean active = tab.getContentId().equals(template.getActiveTabContentId());
                    if (active) {
                        drawPanel(canvas, left + 174, top + 8, left + 226, top + 38);
                    }
                    centerText(canvas, textOf(tab.getTitle()), left + tabWidth / 2,
                            top + 60, 18, active ? TEXT : MUTED, tabWidth - 12);
                    if (tab.getIcon() != null) {
                        drawCarIcon(canvas, tab.getIcon(), left + 32, top + 36, 40);
                    }
                    addTabHit(left, top, left + tabWidth, top + 78,
                            template.getTabCallbackDelegate(), tab.getContentId());
                }
            }
            TabContents contents = template.getTabContents();
            if (contents != null && contents.getTemplate() != null) {
                drawNestedTemplate(canvas, contents.getTemplate(), top + 92);
            } else if (template.isLoading()) {
                text(canvas, "Loading…", 48, top + 140, 20, MUTED);
            }
        }

        private void drawNestedTemplate(Canvas canvas, Template nested) {
            drawNestedTemplate(canvas, nested, contentTop());
        }

        private void drawNestedTemplate(Canvas canvas, Template nested, float top) {
            if (nested instanceof ListTemplate) {
                drawListTemplate(canvas, (ListTemplate) nested);
            } else if (nested instanceof GridTemplate) {
                drawGridTemplate(canvas, (GridTemplate) nested);
            } else if (nested instanceof PaneTemplate) {
                drawPaneTemplate(canvas, (PaneTemplate) nested);
            } else if (nested instanceof MessageTemplate) {
                MessageTemplate message = (MessageTemplate) nested;
                centerText(canvas, textOf(message.getTitle()), getWidth() / 2f,
                        top + 18, 24, TEXT, getWidth() - 100);
                centerText(canvas, textOf(message.getMessage()), getWidth() / 2f,
                        top + 177, 20, TEXT, getWidth() - 160);
                drawCenteredActions(canvas, message.getActions(), top + 201);
            } else if (nested instanceof MapWithContentTemplate) {
                drawMapWithContent(canvas, (MapWithContentTemplate) nested);
            } else {
                text(canvas, nested.getClass().getSimpleName(), 48, top + 140, 20, TEXT);
            }
        }

        private void drawSectionedItemTemplate(Canvas canvas, SectionedItemTemplate template) {
            float top = contentTop();
            androidx.car.app.model.Header header = template.getHeader();
            drawHeader(canvas, header == null ? null : header.getTitle(),
                    header == null ? null : header.getStartHeaderAction(), 24, top + 12);
            float y = top + 84;
            if (template.getSections() != null) {
                for (Section<?> section : template.getSections()) {
                    textBold(canvas, textOf(section.getTitle()), 66, y, 18, TEXT);
                    y += 12;
                    List<Item> items = sectionItems.get(section);
                    if (items == null) {
                        requestSectionItems(section);
                        text(canvas, section.getItemsDelegate().getSize() + " items", 66, y + 22,
                                16, MUTED);
                        y += 70;
                    } else {
                        for (Item item : items) {
                            if (item instanceof Row) {
                                y = drawSectionRow(canvas, (Row) item, y);
                            }
                        }
                        y += 18;
                    }
                }
            }
            drawCenteredActions(canvas, template.getActions(), contentBottom() - 78);
        }

        private void requestSectionItems(Section<?> section) {
            if (!loadingSections.add(section)) return;
            int size = section.getItemsDelegate().getSize();
            if (size <= 0) {
                sectionItems.put(section, new ArrayList<>());
                invalidate();
                return;
            }
            try {
                section.getItemsDelegate().requestItemRange(0,
                        size - 1, new OnDoneCallback() {
                            @Override public void onSuccess(Bundleable response) {
                                List<Item> items = new ArrayList<>();
                                try {
                                    Object value = response == null ? null : response.get();
                                    if (value instanceof List) {
                                        for (Object entry : (List<?>) value) {
                                            if (entry instanceof Item) {
                                                items.add((Item) entry);
                                            } else if (entry instanceof Bundleable) {
                                                Object decoded = ((Bundleable) entry).get();
                                                if (decoded instanceof Item) items.add((Item) decoded);
                                            }
                                        }
                                    }
                                } catch (BundlerException ignored) {
                                    // Keep the section in its loading state if a
                                    // remote delegate cannot be decoded.
                                }
                                sectionItems.put(section, items);
                                invalidate();
                            }
                        });
            } catch (RuntimeException ignored) {
                // The app may be stopping; the next template will retry.
                loadingSections.remove(section);
            }
        }

        private float drawSectionRow(Canvas canvas, Row row, float top) {
            float left = 54;
            float right = getWidth() - 54;
            float height = row.getTexts().isEmpty() ? 62 : 72;
            drawSelectionPanel(canvas, left, top, right, top + height);
            text(canvas, textOf(row.getTitle()), 66, top + 32, 20,
                    row.isEnabled() ? TEXT : MUTED);
            float textY = top + 58;
            for (CarText subtext : row.getTexts()) {
                text(canvas, textOf(subtext), 66, textY, 16, MUTED);
                textY += 20;
            }
            addHit(left, top, right, top + height, row.getOnClickDelegate());
            return top + height;
        }

        private void drawPlaceListMapTemplate(Canvas canvas, PlaceListMapTemplate template) {
            drawContentPanel(canvas, template.getItemList(), template.getTitle(),
                    template.getHeaderAction(), true);
            drawActionStrip(canvas, template.getActionStrip());
            drawMapControls(canvas, panelTop(), panelBottom());
        }

        private void drawRoutePreviewTemplate(Canvas canvas,
                                              RoutePreviewNavigationTemplate template) {
            drawContentPanel(canvas, template.getItemList(), template.getTitle(),
                    template.getHeaderAction(), true);
            drawActionStrip(canvas, template.getActionStrip());
            drawMapActionStack(canvas, template.getMapActionStrip(), panelTop(), panelBottom());
            drawAction(canvas, template.getNavigateAction(), dp(48), panelBottom() - dp(76),
                    dp(245), panelBottom() - dp(20));
        }

        private void drawMediaPlaybackTemplate(Canvas canvas, MediaPlaybackTemplate template) {
            float top = contentTop();
            androidx.car.app.model.Header header = template.getHeader();
            drawHeader(canvas, header == null ? null : header.getTitle(),
                    header == null ? null : header.getStartHeaderAction(), 24, top + 12);
            text(canvas, "Media playback is provided by the AAOS media host.", 66, top + 150,
                    20, TEXT);
            text(canvas, "This template host renders the app shell and delegates media sessions.",
                    66, top + 190, 16, MUTED);
        }

        private void drawHeader(Canvas canvas, @Nullable CarText title,
                                @Nullable Action startAction, float x, float y) {
            if (startAction != null && startAction.getType() == Action.TYPE_BACK) {
                drawBackArrow(canvas, x + dp(8), y + dp(27));
                addBackHit(y, x);
            }
            float titleX = startAction != null && startAction.getType() == Action.TYPE_BACK
                    ? x + dp(56) : x;
            text(canvas, textOf(title), titleX, y + dp(36), 24, TEXT);
            if (startAction != null && startAction.getType() != Action.TYPE_BACK) {
                drawAction(canvas, startAction, x, y, x + dp(150), y + dp(52));
            }
        }

        private void drawAppHeader(Canvas canvas, @Nullable CarText title,
                                   float left, float top, float right) {
            float centerX = left + dp(35);
            float centerY = top + dp(42);
            if (appIcon != null) {
                int size = Math.round(dp(46));
                int iconLeft = Math.round(centerX - size / 2f);
                int iconTop = Math.round(centerY - size / 2f);
                appIcon.setBounds(iconLeft, iconTop, iconLeft + size, iconTop + size);
                appIcon.draw(canvas);
            } else {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.rgb(70, 70, 70));
                canvas.drawCircle(centerX, centerY, dp(23), paint);
            }
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
                    drawHistoryIcon(canvas, x + dp(4), y);
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

        private void drawHistoryIcon(Canvas canvas, float x, float y) {
            float unit = getWidth() / 1080f;
            float left = x - unit * 18f;
            float top = y - unit * 18f;
            paint.setColor(ICON);
            paint.setStyle(Paint.Style.FILL);

            Path loop = new Path();
            loop.moveTo(left + unit * 28.5f, top + unit * 18f);
            loop.cubicTo(left + unit * 28.5f, top + unit * 23.799f,
                    left + unit * 23.799f, top + unit * 28.5f,
                    left + unit * 18f, top + unit * 28.5f);
            loop.cubicTo(left + unit * 15.3998f, top + unit * 28.5f,
                    left + unit * 13.0204f, top + unit * 27.5549f,
                    left + unit * 11.1865f, top + unit * 25.9894f);
            loop.lineTo(left + unit * 9.1285f, top + unit * 28.176f);
            loop.cubicTo(left + unit * 11.501f, top + unit * 30.2461f,
                    left + unit * 14.604f, top + unit * 31.5f,
                    left + unit * 18f, top + unit * 31.5f);
            loop.cubicTo(left + unit * 25.4558f, top + unit * 31.5f,
                    left + unit * 31.5f, top + unit * 25.4558f,
                    left + unit * 31.5f, top + unit * 18f);
            loop.cubicTo(left + unit * 31.5f, top + unit * 10.5442f,
                    left + unit * 25.4558f, top + unit * 4.5f,
                    left + unit * 18f, top + unit * 4.5f);
            loop.cubicTo(left + unit * 10.5442f, top + unit * 4.5f,
                    left + unit * 4.5f, top + unit * 10.5442f,
                    left + unit * 4.5f, top + unit * 18f);
            loop.lineTo(left, top + unit * 18f);
            loop.lineTo(left + unit * 6f, top + unit * 25.5f);
            loop.lineTo(left + unit * 12f, top + unit * 18f);
            loop.lineTo(left + unit * 7.5f, top + unit * 18f);
            loop.cubicTo(left + unit * 7.5f, top + unit * 12.201f,
                    left + unit * 12.201f, top + unit * 7.5f,
                    left + unit * 18f, top + unit * 7.5f);
            loop.cubicTo(left + unit * 23.799f, top + unit * 7.5f,
                    left + unit * 28.5f, top + unit * 12.201f,
                    left + unit * 28.5f, top + unit * 18f);
            loop.close();
            canvas.drawPath(loop, paint);

            Path hands = new Path();
            hands.moveTo(left + unit * 16.5f, top + unit * 13.5f);
            hands.lineTo(left + unit * 16.5f, top + unit * 20.3028f);
            hands.lineTo(left + unit * 21.668f, top + unit * 23.7481f);
            hands.lineTo(left + unit * 23.332f, top + unit * 21.2519f);
            hands.lineTo(left + unit * 19.5f, top + unit * 18.6972f);
            hands.lineTo(left + unit * 19.5f, top + unit * 13.5f);
            hands.close();
            canvas.drawPath(hands, paint);
        }

        private void drawMapActionStrip(Canvas canvas,
                                        @Nullable androidx.car.app.model.ActionStrip strip,
                                        float centerY) {
            drawMapActionStrip(canvas, strip, centerY, false);
        }

        private void drawMapActionStrip(Canvas canvas,
                                        @Nullable androidx.car.app.model.ActionStrip strip,
                                        float centerY, boolean forceSettingsIcon) {
            if (strip == null) return;
            List<Action> actions = strip.getActions();
            float centerX = getWidth() - dp(53);
            for (int i = actions.size() - 1; i >= 0; i--) {
                Action action = actions.get(i);
                float x = centerX - (actions.size() - 1 - i) * dp(94);
                paint.setColor(PANEL);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(x, centerY, dp(40), paint);
                drawActionIcon(canvas, action, x, centerY, forceSettingsIcon || x < centerX);
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

        private boolean isScrollableTemplate() {
            Template template = wrapper == null ? null : wrapper.getTemplate();
            return template instanceof ListTemplate
                    || template instanceof GridTemplate
                    || template instanceof SearchTemplate;
        }

        private float scrollContentTop() {
            Template template = wrapper == null ? null : wrapper.getTemplate();
            if (template instanceof GridTemplate || template instanceof SearchTemplate) {
                return contentTop() + dp(55);
            }
            return contentTop() + dp(78);
        }

        private void scrollListBy(float distance) {
            listScrollOffset = Math.max(0, Math.min(listMaxScroll,
                    listScrollOffset - distance));
            invalidate();
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

        private void addBackHit(float top, float left) {
            hits.add(new Hit(new RectF(left, top, left + dp(90), top + dp(78)), true));
        }

        private void addTabHit(float left, float top, float right, float bottom,
                               @Nullable androidx.car.app.model.TabCallbackDelegate delegate,
                               String contentId) {
            if (delegate != null && contentId != null) {
                hits.add(new Hit(new RectF(left, top, right, bottom), delegate, contentId));
            }
        }

        private void addAlertHit(float left, float top, float right, float bottom,
                                 @Nullable androidx.car.app.model.AlertCallbackDelegate callback,
                                 int alertId,
                                 @Nullable androidx.car.app.model.OnClickDelegate delegate) {
            if (callback != null) {
                hits.add(new Hit(new RectF(left, top, right, bottom), callback, alertId, delegate));
            }
        }

        private void drawContentPanel(Canvas canvas, Template content, boolean overlay) {
            int right = Math.min(getWidth() - 20, 600);
            float left = 20;
            float top = contentTop() + 20;
            float bottom = contentBottom() - 24;
            drawPanel(canvas, left, top, right, bottom);
            if (content instanceof ListTemplate) {
                ListTemplate list = (ListTemplate) content;
                androidx.car.app.model.Header header = list.getHeader();
                drawHeader(canvas,
                        header == null ? list.getTitle() : header.getTitle(),
                        header == null ? list.getHeaderAction() : header.getStartHeaderAction(),
                        44, top + 12);
                drawItems(canvas, list.getSingleList(), 44, (int) top + 84, right - 28);
                if (list.isLoading()) {
                    text(canvas, "Calculating route…", 44, top + 124, 18, MUTED);
                }
            } else if (content instanceof PaneTemplate) {
                PaneTemplate pane = (PaneTemplate) content;
                androidx.car.app.model.Header header = pane.getHeader();
                drawHeader(canvas,
                        header == null ? pane.getTitle() : header.getTitle(),
                        header == null ? pane.getHeaderAction() : header.getStartHeaderAction(),
                        44, top + 12);
                drawPane(canvas, pane.getPane(), 44, (int) top + 84, right - 28);
            } else if (content instanceof PlaceListNavigationTemplate) {
                PlaceListNavigationTemplate list = (PlaceListNavigationTemplate) content;
                title(canvas, list.getTitle(), 44, 60);
                drawItems(canvas, list.getItemList(), 44, 92, right - 28);
            } else {
                text(canvas, content == null ? "Map" : content.getClass().getSimpleName(),
                        44, 60, 22, TEXT);
            }
        }

        private void drawContentPanel(Canvas canvas, @Nullable ItemList list,
                                      @Nullable CarText title, @Nullable Action headerAction,
                                      boolean overlay) {
            int right = Math.min(getWidth() - 20, 600);
            int top = 20;
            int bottom = getHeight() - 24;
            drawPanel(canvas, 20, top, right, bottom);
            drawHeader(canvas, title, headerAction, 44, 24);
            drawItems(canvas, list, 44, 104, right - 28);
        }

        private int drawItems(Canvas canvas, @Nullable ItemList list, int x, int y, int width) {
            if (list == null) return y;
            boolean first = true;
            for (Item item : list.getItems()) {
                if (!(item instanceof Row)) continue;
                Row row = (Row) item;
                int h = row.getTexts().isEmpty() ? 62 : 72;
                if (first) {
                    drawSelectionPanel(canvas, x - 12, y, x + width + 12, y + h);
                    first = false;
                }
                text(canvas, textOf(row.getTitle()), x + 20, y + 30, 19,
                        row.isEnabled() ? TEXT : MUTED);
                int textY = y + 56;
                for (CarText subtext : row.getTexts()) {
                    text(canvas, textOf(subtext), x + 20, textY, 14, MUTED);
                    textY += 18;
                }
                paint.setColor(DIVIDER);
                canvas.drawRect(x, y + h - 1, x + width, y + h, paint);
                addHit(x, y, x + width, y + h, row.getOnClickDelegate());
                y += h;
            }
            return y;
        }

        private float drawSearchItems(Canvas canvas, @Nullable ItemList list, float x, float y,
                                      float width, float scrollOffset) {
            if (list == null) return y;
            float rowTop = y - scrollOffset;
            for (Item item : list.getItems()) {
                if (!(item instanceof Row)) continue;
                Row row = (Row) item;
                float rowHeight = row.getTexts().isEmpty() ? dp(62) : dp(96);
                text(canvas, textOf(row.getTitle()), x + dp(20), rowTop + dp(40), 20,
                        row.isEnabled() ? TEXT : MUTED);
                float textY = rowTop + dp(69);
                for (CarText subtext : row.getTexts()) {
                    textY = drawWrappedText(canvas, textOf(subtext), x + dp(20), textY,
                            width - dp(30), dp(21), MUTED);
                }
                paint.setColor(DIVIDER);
                canvas.drawRect(x, rowTop + rowHeight - 1,
                        x + width, rowTop + rowHeight, paint);
                addHit(x, rowTop, x + width, rowTop + rowHeight,
                        row.getOnClickDelegate());
                rowTop += rowHeight;
            }
            return rowTop + scrollOffset;
        }

        private void drawItemsPanel(Canvas canvas, ItemList list) {
            drawPanel(canvas, 24, 20, Math.min(getWidth() - 24, 620), getHeight() - 28);
            drawItems(canvas, list, 48, 52, Math.min(getWidth() - 56, 570));
        }

        private void drawPane(Canvas canvas, Pane pane, int x, int y, int width) {
            if (pane == null) return;
            for (Row row : pane.getRows()) {
                int h = row.getTexts().isEmpty() ? 72 : 92;
                text(canvas, textOf(row.getTitle()), x + 18, y + 30, 19, TEXT);
                int textY = y + 57;
                for (CarText subtext : row.getTexts()) {
                    text(canvas, textOf(subtext), x + 18, textY, 14, MUTED);
                    textY += 18;
                }
                paint.setColor(DIVIDER);
                canvas.drawRect(x, y + h - 11, x + width, y + h - 10, paint);
                addHit(x, y, x + width, y + h - 10, row.getOnClickDelegate());
                y += h;
            }
            drawActionList(canvas, pane.getActions(), x, y + 8);
        }

        private float routeRowHeight(Row row) {
            return dp(hasRouteSubtext(row) ? 90 : 72);
        }

        private boolean hasRouteSubtext(Row row) {
            for (CarText subtext : row.getTexts()) {
                String value = textOf(subtext).trim();
                if (!value.isEmpty() && !"•".equals(value) && !"·".equals(value)) {
                    return true;
                }
            }
            return false;
        }

        private void drawRoutePane(Canvas canvas, @Nullable Pane pane, float x, float y,
                                   float width) {
            if (pane == null) return;
            float rowTop = y;
            for (Row row : pane.getRows()) {
                float rowHeight = routeRowHeight(row);
                paint.setColor(ACCENT);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(x + dp(8), rowTop + dp(30), dp(5), paint);
                text(canvas, textOf(row.getTitle()), x + dp(26), rowTop + dp(36), dp(19),
                        row.isEnabled() ? TEXT : MUTED);
                if (hasRouteSubtext(row)) {
                    float textY = rowTop + dp(62);
                    for (CarText subtext : row.getTexts()) {
                        String value = textOf(subtext).trim();
                        if (value.isEmpty() || "•".equals(value) || "·".equals(value)) continue;
                        text(canvas, value, x + dp(26), textY, dp(14), MUTED);
                        textY += dp(18);
                    }
                }
                paint.setColor(DIVIDER);
                canvas.drawRect(x, rowTop + rowHeight - 1, x + width,
                        rowTop + rowHeight, paint);
                addHit(x, rowTop, x + width, rowTop + rowHeight, row.getOnClickDelegate());
                rowTop += rowHeight;
            }
            drawRouteActionList(canvas, pane.getActions(), x, rowTop + dp(8), width);
        }

        private void drawRouteActionList(Canvas canvas, @Nullable List<Action> actions,
                                         float x, float y, float width) {
            if (actions == null) return;
            float buttonWidth = Math.min(dp(190), width);
            float offset = 0;
            for (Action action : actions) {
                if (offset + buttonWidth > width) break;
                drawPrimaryAction(canvas, action, x + offset, y,
                        x + offset + buttonWidth, y + dp(54));
                offset += buttonWidth + dp(12);
            }
        }

        private void drawPrimaryAction(Canvas canvas, @Nullable Action action, float left,
                                       float top, float right, float bottom) {
            if (action == null) return;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(action.isEnabled() ? ACCENT : PANEL_ALT);
            canvas.drawRoundRect(left, top, right, bottom, dp(14), dp(14), paint);
            String label = textOf(action.getTitle());
            if (label.isEmpty()) label = actionType(action);
            int labelColor = action.isEnabled() ? Color.rgb(15, 31, 48) : MUTED;
            centerText(canvas, label, (left + right) / 2f, top + dp(35), dp(17),
                    labelColor, right - left - dp(24));
            if (action.getOnClickDelegate() != null) {
                addHit(left, top, right, bottom, action.getOnClickDelegate());
            }
        }

        private void drawSearchField(Canvas canvas, String hint, float top) {
            float left = 24;
            float right = getWidth() - 24;
            paint.setColor(PANEL_ALT);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(left, top, right, top + 44, 24, 24, paint);
            paint.setColor(MUTED);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3);
            canvas.drawCircle(left + 24, top + 21, 8, paint);
            canvas.drawLine(left + 30, top + 27, left + 36, top + 33, paint);
            paint.setStyle(Paint.Style.FILL);
            String value = searchText.isEmpty() ? (hint == null ? "Search" : hint) : searchText;
            text(canvas, value, left + 52, top + 29, 18,
                    searchText.isEmpty() ? MUTED : TEXT);
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawCircle(right - 25, top + 22, 8, paint);
            canvas.drawLine(right - 25, top + 9, right - 25, top + 35, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private void drawCenteredActions(Canvas canvas, @Nullable List<Action> actions, float top) {
            if (actions == null || actions.isEmpty()) return;
            float width = 284;
            float gap = 16;
            float left = (getWidth() - (width * actions.size() + gap * (actions.size() - 1))) / 2f;
            for (Action action : actions) {
                drawAction(canvas, action, left, top, left + width, top + 58);
                left += width + gap;
            }
        }

        private void drawCompactCenteredAction(Canvas canvas, @Nullable List<Action> actions,
                                               float top) {
            if (actions == null || actions.isEmpty()) return;
            float width = 104;
            float left = (getWidth() - width) / 2f;
            Action action = actions.get(0);
            paint.setColor(BG);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(left, top, left + width, top + 52, dp(14), dp(14), paint);
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3);
            canvas.drawRoundRect(left + 1.5f, top + 1.5f, left + width - 1.5f, top + 50.5f,
                    dp(14), dp(14), paint);
            paint.setStyle(Paint.Style.FILL);
            centerText(canvas, textOf(action.getTitle()), getWidth() / 2f, top + 33, 16,
                    TEXT, width - 12);
            if (action.getOnClickDelegate() != null) {
                addHit(left, top, left + width, top + 52, action.getOnClickDelegate());
            }
        }

        private void drawActionList(Canvas canvas, List<Action> actions, float x, float y) {
            if (actions == null) return;
            float offset = 0;
            for (Action action : actions) {
                drawAction(canvas, action, x + offset, y, x + offset + dp(150), y + dp(52));
                offset += dp(162);
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

        private void drawAction(Canvas canvas, @Nullable Action action, float left, float top,
                                float right, float bottom) {
            if (action == null) return;
            drawPanel(canvas, left, top, right, bottom);
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3);
            canvas.drawRoundRect(left + 1.5f, top + 1.5f, right - 1.5f, bottom - 1.5f,
                    dp(16), dp(16), paint);
            paint.setStyle(Paint.Style.FILL);
            String label = textOf(action.getTitle());
            if (label.isEmpty()) label = actionType(action);
            centerText(canvas, label, (left + right) / 2f, top + 34, 16,
                    action.isEnabled() ? TEXT : MUTED, right - left - 20);
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
                                  boolean checked, String toggleKey) {
            if (delegate != null) {
                hits.add(new Hit(new RectF(left, top, right, bottom), delegate, checked,
                        toggleKey));
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

        private void drawSelectionPanel(Canvas canvas, float left, float top, float right,
                                        float bottom) {
            drawPanel(canvas, left, top, right, bottom);
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3);
            canvas.drawRoundRect(left + 1.5f, top + 1.5f, right - 1.5f, bottom - 1.5f,
                    dp(10), dp(10), paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private void text(Canvas canvas, String value, float x, float y, float size, int color) {
            paint.setColor(color);
            paint.setStyle(Paint.Style.FILL);
            paint.setTextSize(size);
            paint.setTypeface(Typeface.create("sans", Typeface.NORMAL));
            canvas.drawText(value == null ? "" : value, x, y, paint);
        }

        private void centerText(Canvas canvas, String value, float centerX, float baseline,
                                float size, int color, float maxWidth) {
            paint.setTextSize(size);
            String rendered = value == null ? "" : value;
            while (rendered.length() > 1 && paint.measureText(rendered) > maxWidth) {
                rendered = rendered.substring(0, rendered.length() - 1);
            }
            text(canvas, rendered, centerX - paint.measureText(rendered) / 2, baseline, size, color);
        }

        private void drawCarIcon(Canvas canvas, androidx.car.app.model.CarIcon carIcon,
                                 float centerX, float centerY, float size) {
            if (carIcon == null) return;
            try {
                Drawable drawable = carIcon.getIcon().loadDrawable(getContext());
                if (drawable != null) {
                    int half = Math.round(size / 2);
                    drawable.setBounds(Math.round(centerX - half), Math.round(centerY - half),
                            Math.round(centerX + half), Math.round(centerY + half));
                    drawable.draw(canvas);
                    return;
                }
            } catch (RuntimeException ignored) {
                // Some app icons are remote-only; use the same familiar app
                // glyph as the stock host instead of an empty placeholder.
            }
            drawAndroidAppIcon(canvas, centerX, centerY, size);
        }

        private void drawAndroidAppIcon(Canvas canvas, float centerX, float centerY, float size) {
            float radius = size / 2;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(137, 201, 239));
            canvas.drawCircle(centerX, centerY, radius, paint);
            canvas.save();
            Path grid = new Path();
            grid.addCircle(centerX, centerY, radius, Path.Direction.CW);
            canvas.clipPath(grid);
            paint.setColor(Color.rgb(177, 220, 247));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1);
            for (float offset = -radius; offset <= radius; offset += size * .18f) {
                canvas.drawLine(centerX + offset, centerY - radius,
                        centerX + offset, centerY + radius, paint);
                canvas.drawLine(centerX - radius, centerY + offset,
                        centerX + radius, centerY + offset, paint);
            }
            canvas.restore();
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.FILL);
            float bodyWidth = size * .56f;
            float bodyHeight = size * .34f;
            float bodyLeft = centerX - bodyWidth / 2;
            float bodyTop = centerY - bodyHeight / 4;
            canvas.drawRoundRect(bodyLeft, bodyTop, bodyLeft + bodyWidth,
                    bodyTop + bodyHeight, size * .08f, size * .08f, paint);
            canvas.drawRect(centerX - bodyWidth / 2, bodyTop + bodyHeight * .55f,
                    centerX + bodyWidth / 2, bodyTop + bodyHeight * 1.18f, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2, size * .035f));
            canvas.drawLine(centerX - bodyWidth * .28f, bodyTop,
                    centerX - bodyWidth * .42f, bodyTop - size * .12f, paint);
            canvas.drawLine(centerX + bodyWidth * .28f, bodyTop,
                    centerX + bodyWidth * .42f, bodyTop - size * .12f, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(90, 160, 205));
            canvas.drawCircle(centerX - bodyWidth * .22f, bodyTop + bodyHeight * .34f,
                    Math.max(2, size * .035f), paint);
            canvas.drawCircle(centerX + bodyWidth * .22f, bodyTop + bodyHeight * .34f,
                    Math.max(2, size * .035f), paint);
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
                if (mapMode) showNavigationControls();
                downX = lastX = event.getX();
                downY = lastY = event.getY();
                dragging = false;
                listDragging = false;
                pressedHit = findHit(event.getX(), event.getY());
                backPressed = pressedHit != null && pressedHit.back;
                if (pressedHit == null && wrapper != null
                        && isScrollableTemplate()
                        && event.getY() >= scrollContentTop()) {
                    listDragging = true;
                }
                if (pressedHit == null && mapMode) {
                    dragging = false;
                }
                scaled = false;
                velocityTracker = VelocityTracker.obtain();
                velocityTracker.addMovement(event);
                scaleDetector.onTouchEvent(event);
                return true;
            }
            if (velocityTracker != null) {
                velocityTracker.addMovement(event);
            }
            scaleDetector.onTouchEvent(event);
            if (action == MotionEvent.ACTION_MOVE) {
                if (mapMode) showNavigationControls();
                float dx = event.getX() - lastX;
                float dy = event.getY() - lastY;
                if (Math.hypot(event.getX() - downX, event.getY() - downY) > dp(8)) {
                    dragging = true;
                }
                if (isScrollableTemplate() && !listDragging && pressedHit != null && dragging) {
                    // A row is a click target until the pointer moves. Once it
                    // becomes a drag, release the pressed target and scroll the
                    // list instead of accidentally activating the row.
                    pressedHit = null;
                    backPressed = false;
                    listDragging = true;
                }
                if (listDragging) {
                    scrollListBy(dy);
                } else if (pressedHit == null && mapMode && !scaled
                        && !scaleDetector.isInProgress() && event.getPointerCount() == 1) {
                    // AndroidX's map callback uses scroll-distance semantics
                    // (previous pointer position minus current position). That
                    // makes the rendered map move opposite the finger, like a
                    // paper map held underneath it.
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
                    if (scaled) {
                        // A pinch is a complete gesture; do not turn its final
                        // pointer-up into a click or fling.
                    } else if (dragging && velocityTracker != null) {
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
                    if (isSearchFieldAt(event.getX(), event.getY())) {
                        startLocalInput();
                    } else if (backPressed) {
                        session.onBackPressed();
                    } else if (hit != null && hit.bounds.contains(event.getX(), event.getY())) {
                        if (hit.alertDelegate != null) {
                            if (hit.delegate != null) {
                                hit.delegate.sendClick(new OnDoneCallback() {});
                            } else {
                                hit.alertDelegate.sendDismiss(new OnDoneCallback() {});
                            }
                            if (alert != null && alert.getId() == hit.alertId) {
                                alert = null;
                                invalidate();
                            }
                        } else if (hit.tabDelegate != null) {
                            hit.tabDelegate.sendTabSelected(hit.tabContentId, new OnDoneCallback() {});
                        } else if (hit.delegate != null) {
                            hit.delegate.sendClick(new OnDoneCallback() {});
                        } else if (hit.toggle != null) {
                            boolean checked = !hit.checked;
                            hit.toggle.sendCheckedChange(checked, new OnDoneCallback() {});
                            if (hit.toggleKey != null) {
                                toggleOverrides.put(hit.toggleKey, checked);
                            }
                            invalidate();
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

        private boolean isSearchFieldAt(float x, float y) {
            Template template = wrapper == null ? null : wrapper.getTemplate();
            if (!(template instanceof SearchTemplate)) return false;
            float top = contentTop() + 10;
            return x >= dp(20) && x <= getWidth() - dp(20)
                    && y >= top && y <= top + dp(62);
        }

        @Override
        public boolean onGenericMotionEvent(MotionEvent event) {
            if (isScrollableTemplate()
                    && event.getActionMasked() == MotionEvent.ACTION_SCROLL) {
                float distance = event.getAxisValue(MotionEvent.AXIS_SCROLL);
                if (distance == 0) {
                    distance = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                }
                if (distance != 0) {
                    scrollListBy(distance * dp(54));
                    return true;
                }
            }
            return super.onGenericMotionEvent(event);
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event) {
            if (handleRotaryKey(event)) return true;
            return super.onKeyDown(keyCode, event);
        }

        boolean handleRotaryKey(KeyEvent event) {
            if (event == null || event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (isScrollableTemplate()) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN
                        || event.getKeyCode() == KeyEvent.KEYCODE_PAGE_DOWN) {
                    scrollListBy(-dp(72));
                    return true;
                }
                if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP
                        || event.getKeyCode() == KeyEvent.KEYCODE_PAGE_UP) {
                    scrollListBy(dp(72));
                    return true;
                }
            }
            return false;
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
            final androidx.car.app.model.TabCallbackDelegate tabDelegate;
            final String tabContentId;
            final androidx.car.app.model.AlertCallbackDelegate alertDelegate;
            final int alertId;
            final boolean checked;
            final String toggleKey;
            final boolean back;
            Hit(RectF bounds, androidx.car.app.model.OnClickDelegate delegate) {
                this.bounds = bounds;
                this.delegate = delegate;
                this.toggle = null;
                this.tabDelegate = null;
                this.tabContentId = null;
                this.alertDelegate = null;
                this.alertId = -1;
                this.checked = false;
                this.toggleKey = null;
                this.back = false;
            }
            Hit(RectF bounds, androidx.car.app.model.OnCheckedChangeDelegate toggle,
                boolean checked, String toggleKey) {
                this.bounds = bounds;
                this.delegate = null;
                this.toggle = toggle;
                this.tabDelegate = null;
                this.tabContentId = null;
                this.alertDelegate = null;
                this.alertId = -1;
                this.checked = checked;
                this.toggleKey = toggleKey;
                this.back = false;
            }
            Hit(RectF bounds, androidx.car.app.model.TabCallbackDelegate tabDelegate,
                String tabContentId) {
                this.bounds = bounds;
                this.delegate = null;
                this.toggle = null;
                this.tabDelegate = tabDelegate;
                this.tabContentId = tabContentId;
                this.alertDelegate = null;
                this.alertId = -1;
                this.checked = false;
                this.toggleKey = null;
                this.back = false;
            }
            Hit(RectF bounds, androidx.car.app.model.AlertCallbackDelegate alertDelegate,
                int alertId, androidx.car.app.model.OnClickDelegate delegate) {
                this.bounds = bounds;
                this.delegate = delegate;
                this.toggle = null;
                this.tabDelegate = null;
                this.tabContentId = null;
                this.alertDelegate = alertDelegate;
                this.alertId = alertId;
                this.checked = false;
                this.toggleKey = null;
                this.back = false;
            }
            Hit(RectF bounds, boolean back) {
                this.bounds = bounds;
                this.delegate = null;
                this.toggle = null;
                this.tabDelegate = null;
                this.tabContentId = null;
                this.alertDelegate = null;
                this.alertId = -1;
                this.checked = false;
                this.toggleKey = null;
                this.back = back;
            }
        }
    }
}
