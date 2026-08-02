# Caramel Vanilla Templates Host

This is the open-source Android Automotive templates host for Caramel Vanilla.
It implements the AndroidX Car App `RendererService` protocol and renders the
serialized template models into a `SurfaceControlViewHost` surface.

The first release is intentionally focused on navigation apps:

* `PlaceListNavigationTemplate`
* `NavigationTemplate`
* `MapTemplate`
* `MapWithContentTemplate`
* `ListTemplate`, `PaneTemplate`, `MessageTemplate`, and `SearchTemplate`
* list rows, action strips, header actions, and AndroidX click delegates
* the navigation app map `SurfaceView` callback
* AndroidX handshake, session lifecycle, app manager, navigation host, and
  basic constraints host

The host targets Android API 30 and newer. It is designed to be installed as a
privileged system app with the package name
`com.android.car.libraries.templates.host`, matching the AOSP Car Templates
Host permission declaration.

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
```

This is a minimal compatible renderer, not a claim of full parity with the
proprietary Google Automotive App Host. Unsupported templates currently show a
clear fallback view and are recorded in the product documentation as they are
implemented.
