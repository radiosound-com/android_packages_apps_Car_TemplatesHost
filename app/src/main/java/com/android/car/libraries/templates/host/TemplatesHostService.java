package com.android.car.libraries.templates.host;

import android.Manifest;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.hardware.display.DisplayManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceControlViewHost;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.car.app.CarAppService;
import androidx.car.app.HandshakeInfo;
import androidx.car.app.IAppHost;
import androidx.car.app.IAppManager;
import androidx.car.app.ICarApp;
import androidx.car.app.ICarHost;
import androidx.car.app.IOnDoneCallback;
import androidx.car.app.ISurfaceCallback;
import androidx.car.app.OnDoneCallback;
import androidx.car.app.SurfaceContainer;
import androidx.car.app.activity.renderer.ICarAppActivity;
import androidx.car.app.activity.renderer.IInsetsListener;
import androidx.car.app.activity.renderer.IRendererCallback;
import androidx.car.app.activity.renderer.IRendererService;
import androidx.car.app.activity.renderer.surface.ISurfaceListener;
import androidx.car.app.activity.renderer.surface.SurfaceWrapper;
import androidx.car.app.model.Action;
import androidx.car.app.model.Alert;
import androidx.car.app.model.CarText;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.Pane;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import androidx.car.app.model.TemplateWrapper;
import androidx.car.app.media.OpenMicrophoneRequest;
import androidx.car.app.media.OpenMicrophoneResponse;
import androidx.car.app.media.CarAudioCallback;
import androidx.car.app.navigation.model.MapTemplate;
import androidx.car.app.navigation.model.MapWithContentTemplate;
import androidx.car.app.navigation.model.NavigationTemplate;
import androidx.car.app.navigation.model.PlaceListNavigationTemplate;
import androidx.car.app.serialization.Bundleable;
import androidx.car.app.serialization.BundlerException;
import androidx.car.app.versioning.CarAppApiLevels;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Small, open-source AndroidX Car App templates host for AAOS.
 *
 * The service deliberately keeps the renderer protocol separate from the view
 * code.  That makes it possible to add more templates without changing the
 * handshake/lifecycle implementation.
 */
public final class TemplatesHostService extends Service {
    private static final String TAG = "CaramelTemplatesHost";
    private static final String APP_HOST = "app";
    private static final String NAVIGATION_HOST = "navigation";
    private static final String CONSTRAINT_HOST = "constraint";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, RendererSession> sessions = new HashMap<>();
    private RendererSession inputSession;
    private final IRendererService.Stub rendererBinder = new IRendererService.Stub() {
        @Override
        public boolean initialize(ICarAppActivity activity, ComponentName component, int displayId) {
            Log.i(TAG, "initialize component=" + component + " display=" + displayId);
            return callOnMain(() -> initializeSession(activity, component, displayId), false);
        }

        @Override
        public boolean onNewIntent(Intent intent, ComponentName component, int displayId) {
            Log.i(TAG, "onNewIntent component=" + component + " intent=" + intent);
            return callOnMain(() -> {
                RendererSession session = sessions.get(component.flattenToString());
                if (session == null) {
                    return false;
                }
                session.onNewIntent(intent, displayId);
                return true;
            }, false);
        }

        @Override
        public void terminate(ComponentName component) {
            main.post(() -> {
                RendererSession session = sessions.remove(component.flattenToString());
                if (session != null) {
                    session.terminate();
                }
            });
        }

        @Override
        public Bundleable performHandshake(ComponentName component, int hostApiLevel)
                throws RemoteException {
            try {
                int api = Math.min(hostApiLevel, CarAppApiLevels.getLatest());
                return Bundleable.create(new HandshakeInfo(getPackageName(), api));
            } catch (BundlerException e) {
                throw new RemoteException("Unable to create templates host handshake: " + e);
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return rendererBinder;
    }

    @Override
    public void onDestroy() {
        for (RendererSession session : new ArrayList<>(sessions.values())) {
            session.terminate();
        }
        sessions.clear();
        super.onDestroy();
    }

    private boolean initializeSession(ICarAppActivity activity, ComponentName component, int displayId) {
        String key = component.flattenToString();
        RendererSession old = sessions.remove(key);
        if (old != null) {
            old.terminate();
        }
        RendererSession session = new RendererSession(activity, component, displayId);
        sessions.put(key, session);
        session.initialize();
        return true;
    }

    private <T> T callOnMain(java.util.concurrent.Callable<T> callable, T fallback) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                return callable.call();
            } catch (Exception e) {
                Log.e(TAG, "Templates host call failed", e);
                return fallback;
            }
        }
        // The renderer methods are synchronous.  The caller is the activity's
        // binder thread, so use a tiny handoff and wait for the result.
        java.util.concurrent.FutureTask<T> task = new java.util.concurrent.FutureTask<>(callable);
        main.post(task);
        try {
            return task.get();
        } catch (Exception e) {
            Log.e(TAG, "Templates host main-thread call failed", e);
            return fallback;
        }
    }

    final class RendererSession {
        private final ICarAppActivity activity;
        private final ComponentName component;
        private int displayId;
        private final IRendererCallback rendererCallback = new RendererCallback();
        private final ISurfaceListener surfaceListener = new SurfaceListener();
        private final ICarHost carHost = new CarHost();
        private final IAppHost appHost = new AppHost();
        private final androidx.car.app.navigation.INavigationHost navigationHost =
                new NavigationHost();
        private final androidx.car.app.constraints.IConstraintHost constraintHost =
                new ConstraintHost();

        private final ServiceConnection appConnection = new AppConnection();
        private ICarApp carApp;
        private IAppManager appManager;
        private ISurfaceCallback appSurfaceCallback;
        private Intent launchIntent;
        private boolean appHandshakeComplete;
        private boolean appCreated;
        private boolean appStarted;
        private boolean appResumed;
        private boolean activityCreated;
        private boolean activityStarted;
        private boolean activityResumed;
        private boolean appBound;
        private SurfaceControlViewHost surfaceHost;
        private HostRootView rootView;
        private TemplateWrapper currentTemplate;
        private android.location.Location lastAppLocation;
        private android.view.inputmethod.EditorInfo searchEditorInfo =
                new android.view.inputmethod.EditorInfo();
        private final androidx.car.app.activity.renderer.IProxyInputConnection inputConnection =
                new SearchInputConnection();
        private MicrophoneSession microphoneSession;
        private Insets windowInsets = Insets.NONE;
        private Insets stableInsets = Insets.NONE;

        RendererSession(ICarAppActivity activity, ComponentName component, int displayId) {
            this.activity = activity;
            this.component = component;
            this.displayId = displayId;
        }

        void initialize() {
            Log.i(TAG, "registering renderer callbacks for " + component);
            try {
                activity.registerRendererCallback(rendererCallback);
                activity.setSurfaceListener(surfaceListener);
                activity.setInsetsListener(new IInsetsListener.Stub() {
                    @Override
                    public void onInsetsChanged(Insets insets) {
                        main.post(() -> updateInsets(insets, stableInsets));
                    }

                    @Override
                    public void onWindowInsetsChanged(Insets insets, Insets stableInsets) {
                        main.post(() -> updateInsets(insets, stableInsets));
                    }
                });
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to initialize renderer callbacks for " + component, e);
            }
        }

        private void updateInsets(Insets insets, Insets stableInsets) {
            this.windowInsets = insets == null ? Insets.NONE : insets;
            this.stableInsets = stableInsets == null ? Insets.NONE : stableInsets;
            if (rootView != null) {
                rootView.setWindowInsets(this.windowInsets, this.stableInsets);
            }
        }

        void onBackPressed() {
            if (appManager != null) {
                try {
                    appManager.onBackPressed(new Done("onBackPressed"));
                } catch (RemoteException e) {
                    Log.e(TAG, "Unable to forward back press", e);
                }
            }
        }

        void startInput() {
            if (inputSession == this) return;
            if (inputSession != null) {
                try {
                    inputSession.activity.onStopInput();
                } catch (RemoteException e) {
                    Log.e(TAG, "Unable to stop previous car search input", e);
                }
            }
            inputSession = this;
            try {
                activity.onStartInput();
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to start car search input", e);
            }
        }

        void stopInput() {
            if (inputSession != this) return;
            inputSession = null;
            try {
                activity.onStopInput();
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to stop car search input", e);
            }
        }

        void onMapScroll(float distanceX, float distanceY) {
            if (appSurfaceCallback == null) return;
            try {
                appSurfaceCallback.onScroll(distanceX, distanceY);
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to forward map scroll", e);
            }
        }

        void onMapFling(float velocityX, float velocityY) {
            if (appSurfaceCallback == null) return;
            try {
                appSurfaceCallback.onFling(velocityX, velocityY);
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to forward map fling", e);
            }
        }

        void onMapScale(float focusX, float focusY, float scaleFactor) {
            if (appSurfaceCallback == null) return;
            try {
                appSurfaceCallback.onScale(focusX, focusY, scaleFactor);
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to forward map scale", e);
            }
        }

        void onMapClick(float x, float y) {
            if (appSurfaceCallback == null) return;
            try {
                appSurfaceCallback.onClick(x, y);
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to forward map click", e);
            }
        }

        void onNewIntent(Intent intent, int newDisplayId) {
            Log.i(TAG, "session received intent for " + component);
            launchIntent = new Intent(intent);
            displayId = newDisplayId;
            if (carApp != null && appCreated) {
                try {
                    carApp.onNewIntent(launchIntent, new Done("onNewIntent"));
                } catch (RemoteException e) {
                    Log.e(TAG, "Unable to forward new intent", e);
                }
            } else {
                bindToCarApp();
            }
        }

        void terminate() {
            if (inputSession == this) {
                inputSession = null;
                try {
                    activity.onStopInput();
                } catch (RemoteException e) {
                    Log.e(TAG, "Unable to stop terminated car search input", e);
                }
            }
            if (surfaceHost != null) {
                surfaceHost.release();
                surfaceHost = null;
            }
            if (appBound) {
                try {
                    unbindService(appConnection);
                } catch (IllegalArgumentException ignored) {
                }
                appBound = false;
            }
            carApp = null;
            appManager = null;
            appSurfaceCallback = null;
            currentTemplate = null;
            lastAppLocation = null;
            stopMicrophone();
        }

        private void bindToCarApp() {
            if (appBound || launchIntent == null) {
                return;
            }
            Intent bindIntent = new Intent(CarAppService.SERVICE_INTERFACE);
            bindIntent.setComponent(component);
            androidx.car.app.SessionInfo sessionInfo =
                    androidx.car.app.SessionInfoIntentEncoder.containsSessionInfo(launchIntent)
                            ? androidx.car.app.SessionInfoIntentEncoder.decode(launchIntent)
                            : androidx.car.app.SessionInfo.DEFAULT_SESSION_INFO;
            androidx.car.app.SessionInfoIntentEncoder.encode(sessionInfo, bindIntent);
            appBound = bindService(bindIntent, appConnection, Context.BIND_AUTO_CREATE);
            if (!appBound) {
                Log.e(TAG, "Unable to bind to CarAppService " + component);
            }
        }

        private void onAppConnected(IBinder binder) {
            Log.i(TAG, "CarAppService connected for " + component);
            carApp = ICarApp.Stub.asInterface(binder);
            try {
                Bundleable handshake = Bundleable.create(
                        new HandshakeInfo(getPackageName(), CarAppApiLevels.getLatest()));
                carApp.onHandshakeCompleted(handshake, new Done("onHandshakeCompleted") {
                    @Override
                    public void onSuccess(@Nullable Bundleable response) {
                        main.post(() -> {
                            Log.i(TAG, "CarAppService handshake completed for " + component);
                            appHandshakeComplete = true;
                            ensureAppCreated();
                        });
                    }
                });
            } catch (RemoteException | BundlerException | RuntimeException e) {
                Log.e(TAG, "Car app handshake failed for " + component, e);
            }
        }

        private void ensureAppCreated() {
            if (carApp == null || !appHandshakeComplete || appCreated || launchIntent == null
                    || !activityCreated) {
                return;
            }
            Log.i(TAG, "calling onAppCreate for " + component);
            try {
                carApp.onAppCreate(carHost, launchIntent,
                        new Configuration(getResources().getConfiguration()),
                        new Done("onAppCreate") {
                            @Override
                            public void onSuccess(@Nullable Bundleable response) {
                                main.post(() -> {
                                    Log.i(TAG, "CarAppService onAppCreate completed for " + component);
                                    appCreated = true;
                                    requestAppManager();
                                    ensureAppStarted();
                                });
                            }
                        });
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to create car app session", e);
            }
        }

        private void ensureAppStarted() {
            if (carApp == null || !appCreated || !activityStarted || appStarted) {
                return;
            }
            appStarted = true;
            Log.i(TAG, "calling onAppStart for " + component);
            try {
                carApp.onAppStart(new Done("onAppStart") {
                    @Override
                    public void onSuccess(@Nullable Bundleable response) {
                        main.post(RendererSession.this::requestTemplate);
                    }
                });
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to start car app", e);
            }
        }

        private void ensureAppResumed() {
            if (carApp == null || !appStarted || !activityResumed || appResumed) {
                return;
            }
            appResumed = true;
            Log.i(TAG, "calling onAppResume for " + component);
            try {
                carApp.onAppResume(new Done("onAppResume") {
                    @Override
                    public void onSuccess(@Nullable Bundleable response) {
                        main.post(RendererSession.this::requestTemplate);
                    }
                });
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to resume car app", e);
            }
        }

        private void requestAppManager() {
            if (carApp == null || appManager != null) {
                return;
            }
            try {
                carApp.getManager(APP_HOST, new Done("getManager") {
                    @Override
                    public void onSuccess(@Nullable Bundleable response) {
                        main.post(() -> {
                            Object value = unwrap(response);
                            if (value instanceof IAppManager) {
                                appManager = (IAppManager) value;
                                requestTemplate();
                            } else if (value instanceof IBinder) {
                                appManager = IAppManager.Stub.asInterface((IBinder) value);
                                requestTemplate();
                            } else {
                                Log.w(TAG, "Car app returned no app manager: " + value);
                            }
                        });
                    }
                });
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to obtain app manager", e);
            }
        }

        private void requestTemplate() {
            if (appManager == null) {
                return;
            }
            Log.i(TAG, "requesting template for " + component);
            try {
                appManager.getTemplate(new Done("getTemplate") {
                    @Override
                    public void onSuccess(@Nullable Bundleable response) {
                        main.post(() -> {
                            Object value = unwrap(response);
                            if (value instanceof TemplateWrapper) {
                                Log.i(TAG, "received template " + ((TemplateWrapper) value).getTemplate().getClass().getSimpleName());
                                currentTemplate = (TemplateWrapper) value;
                                if (rootView != null) {
                                    rootView.render(currentTemplate);
                                }
                            } else {
                                Log.w(TAG, "Car app returned no template: " + value);
                            }
                        });
                    }
                });
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to request template", e);
            }
        }

        private Object unwrap(Bundleable response) {
            if (response == null) {
                return null;
            }
            try {
                return response.get();
            } catch (BundlerException e) {
                Log.e(TAG, "Unable to decode car app response", e);
                return null;
            }
        }

        private void createSurface(SurfaceWrapper wrapper) {
            Log.i(TAG, "creating hosted surface " + wrapper.getWidth() + "x" + wrapper.getHeight());
            if (surfaceHost != null) {
                surfaceHost.release();
            }
            DisplayManager displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
            Display display = displayManager.getDisplay(wrapper.getDisplayId());
            if (display == null) {
                display = displayManager.getDisplay(displayId);
            }
            if (display == null) {
                Log.e(TAG, "No display for template surface: " + wrapper.getDisplayId());
                return;
            }
            Context displayContext = createDisplayContext(display);
            // The renderer wrapper reports the app-side density (171 dpi on the
            // emulator), but the stock host publishes the physical display
            // density (120 dpi) to the car app's map surface. Passing the
            // wrapper value makes OsmAnd scale map labels roughly twice as large.
            int surfaceDensityDpi = displayContext.getResources().getDisplayMetrics().densityDpi;
            Drawable appIcon = null;
            try {
                appIcon = getPackageManager().getApplicationIcon(component.getPackageName());
            } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
                // A renderer client may disappear between the handshake and
                // surface creation; the host can still render without its icon.
            }
            rootView = new HostRootView(displayContext, surfaceDensityDpi, this, appIcon);
            rootView.setWindowInsets(windowInsets, stableInsets);
            surfaceHost = new SurfaceControlViewHost(displayContext, display, wrapper.getHostToken());
            surfaceHost.setView(rootView, Math.max(1, wrapper.getWidth()),
                    Math.max(1, wrapper.getHeight()));
            try {
                activity.setSurfacePackage(Bundleable.create(surfaceHost.getSurfacePackage()));
            } catch (RemoteException | BundlerException e) {
                Log.e(TAG, "Unable to publish template surface", e);
            }
            if (currentTemplate != null) {
                rootView.render(currentTemplate);
            }
        }

        private void destroySurface() {
            if (rootView != null) {
                rootView.destroy();
                rootView = null;
            }
            if (surfaceHost != null) {
                surfaceHost.release();
                surfaceHost = null;
            }
        }

        private void handleLifecycle(int event) {
            Log.i(TAG, "activity lifecycle " + event + " for " + component);
            switch (event) {
                case 1:
                    activityCreated = true;
                    ensureAppCreated();
                    break;
                case 2:
                    activityStarted = true;
                    ensureAppCreated();
                    ensureAppStarted();
                    break;
                case 3:
                    // CarAppActivity registers the renderer after its own
                    // onCreate/onStart in this launch path. AndroidX then
                    // replays only the current cached event, so reconstruct
                    // the required ordering for the app session.
                    if (!activityCreated) {
                        activityCreated = true;
                        ensureAppCreated();
                    }
                    if (!activityStarted) {
                        activityStarted = true;
                        ensureAppStarted();
                    }
                    activityResumed = true;
                    ensureAppResumed();
                    break;
                case 4:
                    activityResumed = false;
                    if (carApp != null && appResumed) {
                        appResumed = false;
                        try {
                            carApp.onAppPause(new Done("onAppPause"));
                        } catch (RemoteException e) {
                            Log.e(TAG, "Unable to pause car app", e);
                        }
                    }
                    break;
                case 5:
                    activityStarted = false;
                    if (carApp != null && appStarted) {
                        appStarted = false;
                        try {
                            carApp.onAppStop(new Done("onAppStop"));
                        } catch (RemoteException e) {
                            Log.e(TAG, "Unable to stop car app", e);
                        }
                    }
                    break;
                case 6:
                    terminate();
                    break;
                default:
                    break;
            }
        }

        private final class RendererCallback extends IRendererCallback.Stub {
            @Override
            public void onBackPressed() {
                main.post(() -> {
                    if (appManager != null) {
                        try {
                            appManager.onBackPressed(new Done("onBackPressed"));
                        } catch (RemoteException e) {
                            Log.e(TAG, "Unable to forward back press", e);
                        }
                    } else {
                        try {
                            activity.finishCarApp();
                        } catch (RemoteException e) {
                            Log.e(TAG, "Unable to finish car app", e);
                        }
                    }
                });
            }

            @Override public void onCreate() { main.post(() -> handleLifecycle(1)); }
            @Override public void onStart() { main.post(() -> handleLifecycle(2)); }
            @Override public void onResume() { main.post(() -> handleLifecycle(3)); }
            @Override public void onPause() { main.post(() -> handleLifecycle(4)); }
            @Override public void onStop() { main.post(() -> handleLifecycle(5)); }
            @Override public void onDestroyed() { main.post(() -> handleLifecycle(6)); }

            @Override
            public androidx.car.app.activity.renderer.IProxyInputConnection onCreateInputConnection(
                    android.view.inputmethod.EditorInfo editorInfo) {
                searchEditorInfo = editorInfo == null
                        ? new android.view.inputmethod.EditorInfo() : editorInfo;
                if (searchEditorInfo.inputType == 0) {
                    searchEditorInfo.inputType = android.text.InputType.TYPE_CLASS_TEXT;
                }
                searchEditorInfo.imeOptions =
                        android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH;
                if (searchEditorInfo.hintText == null) {
                    searchEditorInfo.hintText = "Search places";
                }
                return inputConnection;
            }
        }

        /**
         * The renderer activity owns the IME connection, while the car app owns
         * the search callback. This small binder proxy keeps that seam alive
         * without requiring the host to expose its private Canvas view.
         */
        private final class SearchInputConnection
                extends androidx.car.app.activity.renderer.IProxyInputConnection.Stub {
            @Override public CharSequence getTextBeforeCursor(int length, int flags) {
                return rootView == null ? "" : rootView.templateView.getSearchText(length);
            }
            @Override public CharSequence getTextAfterCursor(int length, int flags) { return ""; }
            @Override public CharSequence getSelectedText(int flags) { return ""; }
            @Override public int getCursorCapsMode(int reqModes) { return 0; }
            @Override public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                return rootView != null && rootView.templateView.deleteSearchText(beforeLength);
            }
            @Override public boolean setComposingText(CharSequence text, int newCursorPosition) {
                return commitSearch(text);
            }
            @Override public boolean setComposingRegion(int start, int end) { return true; }
            @Override public boolean finishComposingText() { return true; }
            @Override public boolean commitText(CharSequence text, int newCursorPosition) {
                return commitSearch(text);
            }
            @Override public boolean setSelection(int start, int end) { return true; }
            @Override public boolean performEditorAction(int actionCode) {
                if (rootView != null) rootView.templateView.submitSearchText();
                return true;
            }
            @Override public boolean performContextMenuAction(int id) { return false; }
            @Override public boolean beginBatchEdit() { return true; }
            @Override public boolean endBatchEdit() { return true; }
            @Override public boolean sendKeyEvent(android.view.KeyEvent event) {
                if (event != null && event.getAction() == android.view.KeyEvent.ACTION_DOWN
                        && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER) {
                    if (rootView != null) rootView.templateView.submitSearchText();
                }
                return true;
            }
            @Override public boolean clearMetaKeyStates(int states) { return true; }
            @Override public boolean reportFullscreenMode(boolean enabled) { return true; }
            @Override public boolean performPrivateCommand(String action, android.os.Bundle data) {
                return false;
            }
            @Override public boolean requestCursorUpdates(int cursorUpdateMode) { return false; }
            @Override public boolean commitCorrection(android.view.inputmethod.CorrectionInfo correctionInfo) {
                return false;
            }
            @Override public boolean commitCompletion(android.view.inputmethod.CompletionInfo text) {
                return false;
            }
            @Override public android.view.inputmethod.ExtractedText getExtractedText(
                    android.view.inputmethod.ExtractedTextRequest request, int flags) {
                android.view.inputmethod.ExtractedText result =
                        new android.view.inputmethod.ExtractedText();
                result.text = rootView == null ? "" : rootView.templateView.getSearchText(0);
                result.selectionStart = result.selectionEnd = result.text.length();
                return result;
            }
            @Override public void closeConnection() { }
            @Override public android.view.inputmethod.EditorInfo getEditorInfo() {
                return searchEditorInfo;
            }
            @Override public Bundleable getSurroundingText(int before, int after, int flags) {
                return null;
            }
            @Override public boolean deleteSurroundingTextInCodePoints(int before, int after) {
                return deleteSurroundingText(before, after);
            }
            @Override public boolean commitContent(Bundleable inputContent, int flags,
                                                   android.os.Bundle opts) { return false; }

            private boolean commitSearch(CharSequence text) {
                if (rootView == null) return false;
                rootView.templateView.replaceSearchText(text == null ? "" : text.toString());
                return true;
            }
        }

        private final class SurfaceListener extends ISurfaceListener.Stub {
            @Override
            public void onSurfaceAvailable(Bundleable value) {
                Log.i(TAG, "template surface available for " + component);
                Object wrapper = unwrap(value);
                if (wrapper instanceof SurfaceWrapper) {
                    main.post(() -> createSurface((SurfaceWrapper) wrapper));
                }
            }

            @Override
            public void onSurfaceChanged(Bundleable value) {
                Object wrapper = unwrap(value);
                if (wrapper instanceof SurfaceWrapper) {
                    SurfaceWrapper surfaceWrapper = (SurfaceWrapper) wrapper;
                    main.post(() -> {
                        if (surfaceHost != null && rootView != null) {
                            surfaceHost.setView(rootView,
                                    Math.max(1, surfaceWrapper.getWidth()),
                                    Math.max(1, surfaceWrapper.getHeight()));
                            publishMapSurface();
                        }
                    });
                }
            }

            @Override
            public void onSurfaceDestroyed(Bundleable value) {
                main.post(RendererSession.this::destroySurface);
            }
        }

        private final class AppConnection implements ServiceConnection {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                main.post(() -> onAppConnected(service));
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                main.post(() -> {
                    carApp = null;
                    appManager = null;
                    appHandshakeComplete = false;
                    appCreated = false;
                    appStarted = false;
                    appResumed = false;
                });
            }
        }

        private final class CarHost extends ICarHost.Stub {
            @Override
            public void startCarApp(Intent intent) throws RemoteException {
                activity.startCarApp(intent);
            }

            @Override
            public IBinder getHost(String hostName) {
                if (APP_HOST.equals(hostName)) {
                    return appHost.asBinder();
                }
                if (NAVIGATION_HOST.equals(hostName)) {
                    return navigationHost.asBinder();
                }
                if (CONSTRAINT_HOST.equals(hostName)) {
                    return constraintHost.asBinder();
                }
                return null;
            }

            @Override
            public void finish() throws RemoteException {
                activity.finishCarApp();
            }
        }

        private final class AppHost extends IAppHost.Stub {
            @Override
            public void invalidate() {
                main.post(RendererSession.this::requestTemplate);
            }

            @Override
            public void showToast(CharSequence text, int duration) {
                Log.i(TAG, "Car app toast: " + text);
                main.post(() -> {
                    if (rootView != null) {
                        rootView.showToast(text);
                    }
                });
            }

            @Override
            public void setSurfaceCallback(ISurfaceCallback callback) {
                appSurfaceCallback = callback;
                main.post(RendererSession.this::publishMapSurface);
            }

            @Override
            public void sendLocation(android.location.Location location) {
                lastAppLocation = location;
                Log.d(TAG, "Car app location update: " + location);
            }

            @Override
            public void showAlert(Bundleable alert) {
                Object value = unwrap(alert);
                if (value instanceof Alert) {
                    Alert renderedAlert = (Alert) value;
                    Log.i(TAG, "Car app alert requested id=" + renderedAlert.getId());
                    main.post(() -> {
                        if (rootView != null) {
                            rootView.showAlert(renderedAlert);
                        }
                    });
                } else {
                    Log.w(TAG, "Car app sent an invalid alert: " + value);
                }
            }

            @Override
            public void dismissAlert(int alertId) {
                main.post(() -> {
                    if (rootView != null) {
                        rootView.dismissAlert(alertId);
                    }
                });
            }

            @Override
            public Bundleable openMicrophone(Bundleable request) {
                Object value = unwrap(request);
                if (!(value instanceof OpenMicrophoneRequest)) {
                    Log.w(TAG, "Car app sent an invalid microphone request: " + value);
                    return null;
                }
                try {
                    return RendererSession.this.openMicrophone((OpenMicrophoneRequest) value);
                } catch (IOException | SecurityException | IllegalStateException e) {
                    Log.e(TAG, "Unable to open the car microphone", e);
                    stopMicrophone();
                    return null;
                }
            }
        }

        private Bundleable openMicrophone(OpenMicrophoneRequest request)
                throws IOException {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "Microphone permission is not granted to the templates host");
                return null;
            }
            stopMicrophone();
            microphoneSession = new MicrophoneSession(request);
            microphoneSession.start();
            try {
                return Bundleable.create(microphoneSession.response);
            } catch (BundlerException e) {
                stopMicrophone();
                throw new IOException("Unable to serialize microphone response", e);
            }
        }

        private void stopMicrophone() {
            if (microphoneSession != null) {
                microphoneSession.stop();
                microphoneSession = null;
            }
        }

        private final class MicrophoneSession {
            private static final int SAMPLE_RATE = 16000;
            private final OpenMicrophoneRequest request;
            private final ParcelFileDescriptor[] pipe;
            private final AudioRecord audioRecord;
            private final Thread worker;
            private volatile boolean running;
            private final OpenMicrophoneResponse response;

            MicrophoneSession(OpenMicrophoneRequest request) throws IOException {
                this.request = request;
                int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (minimum <= 0) {
                    throw new IllegalStateException("AudioRecord returned no usable buffer size");
                }
                pipe = ParcelFileDescriptor.createPipe();
                audioRecord = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, Math.max(minimum, 4096));
                final androidx.car.app.media.CarAudioCallbackDelegate callback =
                        request.getCarAudioCallbackDelegate();
                CarAudioCallback stopCallback = () -> {
                    if (callback != null) callback.onStopRecording();
                    stop();
                };
                response = new OpenMicrophoneResponse.Builder(stopCallback)
                        .setCarMicrophoneDescriptor(pipe[0]).build();
                worker = new Thread(this::record, "CaramelTemplatesHost-Microphone");
            }

            void start() {
                running = true;
                audioRecord.startRecording();
                worker.start();
            }

            void record() {
                byte[] buffer = new byte[4096];
                try (FileOutputStream output = new FileOutputStream(pipe[1].getFileDescriptor())) {
                    while (running) {
                        int count = audioRecord.read(buffer, 0, buffer.length);
                        if (count > 0) {
                            output.write(buffer, 0, count);
                            output.flush();
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    if (running) Log.w(TAG, "Microphone pipe stopped", e);
                }
            }

            void stop() {
                running = false;
                try {
                    audioRecord.stop();
                } catch (RuntimeException ignored) {
                }
                try {
                    pipe[1].close();
                } catch (IOException ignored) {
                }
                try {
                    pipe[0].close();
                } catch (IOException ignored) {
                }
            }
        }

        void publishMapSurface() {
            if (rootView == null || appSurfaceCallback == null || !rootView.mapSurface.isReady()) {
                return;
            }
            try {
                SurfaceContainer container = new SurfaceContainer(
                        rootView.mapSurface.getHolder().getSurface(),
                        rootView.mapSurface.getWidth(),
                        rootView.mapSurface.getHeight(),
                        rootView.densityDpi);
                appSurfaceCallback.onSurfaceAvailable(Bundleable.create(container), new Done("mapSurface"));
                Rect area = new Rect(0, 0, rootView.mapSurface.getWidth(), rootView.mapSurface.getHeight());
                appSurfaceCallback.onVisibleAreaChanged(area, new Done("visibleArea"));
                appSurfaceCallback.onStableAreaChanged(area, new Done("stableArea"));
            } catch (RemoteException | BundlerException e) {
                Log.e(TAG, "Unable to publish map surface", e);
            }
        }

        private final class NavigationHost extends androidx.car.app.navigation.INavigationHost.Stub {
            @Override public void navigationStarted() {
                Log.i(TAG, "Car app navigation started: " + component);
            }
            @Override public void navigationEnded() {
                Log.i(TAG, "Car app navigation ended: " + component);
            }
            @Override public void updateTrip(Bundleable trip) {
                Log.d(TAG, "Car app trip update received: " + component);
            }
        }

        private final class ConstraintHost extends androidx.car.app.constraints.IConstraintHost.Stub {
            @Override public int getContentLimit(int templateType) {
                switch (templateType) {
                    case androidx.car.app.constraints.ConstraintManager.CONTENT_LIMIT_TYPE_GRID:
                        return 24;
                    case androidx.car.app.constraints.ConstraintManager.CONTENT_LIMIT_TYPE_ROUTE_LIST:
                        return 6;
                    case androidx.car.app.constraints.ConstraintManager.CONTENT_LIMIT_TYPE_PANE:
                        return 4;
                    case androidx.car.app.constraints.ConstraintManager.CONTENT_LIMIT_TYPE_PLACE_LIST:
                    case androidx.car.app.constraints.ConstraintManager.CONTENT_LIMIT_TYPE_LIST:
                    default:
                        return 12;
                }
            }
            @Override public boolean isAppDrivenRefreshEnabled() { return true; }
        }

        private class Done extends IOnDoneCallback.Stub implements OnDoneCallback {
            private final String operation;

            Done(String operation) {
                this.operation = operation;
            }

            @Override
            public void onSuccess(@Nullable Bundleable response) {
            }

            @Override
            public void onFailure(@Nullable Bundleable response) {
                Log.w(TAG, "Car app operation failed: " + operation + " response=" + unwrap(response));
            }
        }
    }
}
