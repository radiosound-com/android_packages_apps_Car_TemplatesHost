# Caramel Vanilla Templates Host

This is the open-source Android Automotive templates host for Caramel Vanilla.
It implements the AndroidX Car App `RendererService` protocol and renders the
serialized template models into a `SurfaceControlViewHost` surface.

Copyright 2026 Radio Sound, Inc. The original source in this repository is
licensed under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0).
AndroidX and AOSP dependencies retain their upstream licenses and attributions.

Published Caramel Vanilla source:

* [Templates host](https://github.com/radiosound-com/android_packages_apps_Car_TemplatesHost)
* [Product manifest](https://github.com/radiosound-com/caramel-vanilla-manifest)
* [OsmAnd product packaging](https://github.com/radiosound-com/android_vendor_osmand)
* [Raspberry Pi 5 device integration](https://github.com/radiosound-com/android_device_brcm_rpi5/tree/caramel-vanilla-aaos)
* [OsmAnd AAOS fork](https://github.com/radiosound-com/OsmAnd/tree/caramel-vanilla-osmand-aaos)

The renderer is organized in two layers: `TemplatesHostService` owns the
AndroidX binder handshake, app lifecycle, surface lifecycle, host callbacks,
and input proxy; `HostRootView` owns model-to-pixel rendering and hit testing.
That boundary is intentional so the host can grow without duplicating the
fragile renderer protocol.

The current renderer covers every concrete AndroidX Car App 1.7 template type:

* Navigation: `PlaceListNavigationTemplate`, `NavigationTemplate`,
  `MapTemplate`, `MapWithContentTemplate`, `PlaceListMapTemplate`, and
  `RoutePreviewNavigationTemplate`
* Content: `ListTemplate`, `GridTemplate`, `SectionedItemTemplate`,
  `PaneTemplate`, `MessageTemplate`, `LongMessageTemplate`, `SearchTemplate`,
  `SignInTemplate`, and `TabTemplate`
* Media shell: `MediaPlaybackTemplate` (playback itself remains owned by the
  AAOS media host and the app's `MediaSession`)
* rows, grid items, action strips, header actions, tabs, toggles, alerts, and
  AndroidX click/search/tab delegates
* navigation map surface callbacks, gesture forwarding, input proxy, app
  invalidation, lifecycle recovery, content limits, and host callbacks

The host targets Android API 30 and newer. It is designed to be installed as a
product privileged app with the package name
`com.android.car.libraries.templates.host`, matching the AOSP Car Templates
Host permission and feature declaration. The product integration requests the
cluster, navigation-manager, boot, and foreground-service permissions used by
the AOSP host contract; the product's privapp allowlist grants the car-specific
permissions.

## Build

```sh
./gradlew :app:assembleDebug
```

The AOSP product imports the resulting APK as a prebuilt and re-signs it with
the platform certificate. The host is selected by AndroidX's
`android.car.template.host.RendererService` intent, so an image must contain
exactly one implementation of that service.

The reference AAOS landscape layout can be smoke-tested against the emulator
profile used by Caramel Vanilla:

```sh
./scripts/check-stock-layout.sh
./scripts/check-settings-and-map.sh
```

The implementation is open source and intentionally does not copy Google’s
private renderer. Visual conformance is tested against the stock host on the
Caramel Vanilla AVD. The contract smoke test is:

```sh
./scripts/check-template-coverage.sh
```

The optional conformance app emits the model family one mode at a time. It is
useful for stock-host/custom-host screenshot comparisons:

```sh
./gradlew :conformance-app:assembleDebug
adb install -r conformance-app/build/outputs/apk/debug/conformance-app-debug.apk
adb shell am start -W -n com.android.car.libraries.templates.conformance/androidx.car.app.activity.CarAppActivity --es mode grid
```

Supported modes are `grid`, `long-message`, `sign-in`, `tabs`, `sections`,
`place-map`, `route-preview`, `navigation`, `media`, `list`, `pane`,
`message`, and `search`.

After installing the conformance APK, the complete AVD matrix is:

```sh
./scripts/check-conformance-matrix.sh
```

Remaining parity work is tracked in the
[Templates Host GitHub issues](https://github.com/radiosound-com/android_packages_apps_Car_TemplatesHost/issues).
