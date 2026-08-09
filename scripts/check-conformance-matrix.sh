#!/bin/sh
# Copyright 2026 Radio Sound, Inc.
# Licensed under the Apache License, Version 2.0.

set -eu

ADB=${ADB:-adb}
PACKAGE=${PACKAGE:-com.android.car.libraries.templates.conformance}
COMPONENT="$PACKAGE/androidx.car.app.activity.CarAppActivity"
HOST=${HOST:-com.android.car.libraries.templates.host}

# Map templates request location through the Car App Library. Grant the
# conformance app's declared runtime permissions on test users when the image
# permits shell grants; hosts that do not expose runtime location simply keep
# the map fixture in its loading state.
"$ADB" shell pm grant --user 10 "$PACKAGE" android.permission.ACCESS_FINE_LOCATION \
    2>/dev/null || true
"$ADB" shell pm grant --user 10 "$PACKAGE" android.permission.ACCESS_COARSE_LOCATION \
    2>/dev/null || true

for mode in grid long-message sign-in tabs sections place-map route-preview navigation media list pane message search; do
    "$ADB" shell am force-stop "$PACKAGE"
    # Restarting the renderer makes every mode a fresh handshake/surface test.
    "$ADB" shell am force-stop "$HOST" 2>/dev/null || true
    "$ADB" logcat -c
    "$ADB" shell am start -W -n "$COMPONENT" --es mode "$mode" >/dev/null
    sleep 2
    if "$ADB" logcat -d -v brief | rg -q \
            "FATAL EXCEPTION|Process: $PACKAGE"; then
        echo "FAIL: conformance mode $mode crashed" >&2
        "$ADB" logcat -d -v brief | rg -A12 'FATAL EXCEPTION|Caused by:' | tail -24 >&2 || true
        exit 1
    fi
    template=$(
        "$ADB" logcat -d -v brief | rg 'received template|Host received new template' | tail -1 \
            | sed 's/.*received //' || true
    )
    if [ -z "$template" ]; then
        echo "FAIL: conformance mode $mode did not reach the renderer" >&2
        exit 1
    fi
    echo "PASS: $mode -> $template"
done

echo "PASS: all AndroidX Car App template conformance modes rendered"
