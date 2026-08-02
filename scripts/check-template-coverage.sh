#!/bin/sh
set -eu

# Contract check for the AndroidX Car App 1.7 template family. This is a
# fast source-level guard: it catches a newly added model type being silently
# routed to the fallback renderer before an AVD screenshot test is run.
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
VIEW="$ROOT/app/src/main/java/com/android/car/libraries/templates/host/HostRootView.java"
SERVICE="$ROOT/app/src/main/java/com/android/car/libraries/templates/host/TemplatesHostService.java"

templates='GridTemplate LongMessageTemplate MessageTemplate PaneTemplate PlaceListMapTemplate SearchTemplate SectionedItemTemplate TabTemplate ListTemplate MapTemplate MapWithContentTemplate NavigationTemplate PlaceListNavigationTemplate RoutePreviewNavigationTemplate MediaPlaybackTemplate SignInTemplate'
for template in $templates; do
    if ! rg -q "instanceof $template" "$VIEW"; then
        echo "FAIL: $template is not dispatched by HostRootView" >&2
        exit 1
    fi
done

for contract in \
    'onCreateInputConnection' \
    'sendSearchTextChanged' \
    'sendSearchSubmitted' \
    'showAlert' \
    'dismissAlert' \
    'onSurfaceChanged' \
    'navigationStarted' \
    'navigationEnded' \
    'updateTrip'; do
    if ! rg -q "$contract" "$SERVICE" "$VIEW"; then
        echo "FAIL: renderer contract is not covered: $contract" >&2
        exit 1
    fi
done

echo "PASS: AndroidX Car App 1.7 template and renderer contracts are covered"
