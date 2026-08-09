#!/bin/sh
# Copyright 2026 Radio Sound, Inc.
# Licensed under the Apache License, Version 2.0.

set -u

ADB=${ADB:-adb}
SERIAL=${ANDROID_SERIAL:-emulator-5568}
OUT_DIR=${OUT_DIR:-/tmp/caramel-osmand-audit}
TMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/caramel-osmand-safety.XXXXXX")
PACKAGE=net.osmand.dev
ACTIVITY="$PACKAGE/androidx.car.app.activity.CarAppActivity"
VOICE_PACKAGE=com.radiosound.caramelvoice
PRODUCT=caramel_car_arm64_kokoro

adb_cmd() {
    "$ADB" -s "$SERIAL" "$@"
}

log() {
    printf '%s\n' "[DEBUG-AUDIT] $*"
}

failures=0
fail() {
    printf '%s\n' "FAIL: $*"
    failures=$((failures + 1))
}

pass() {
    printf '%s\n' "PASS: $*"
}

capture() {
    mkdir -p "$OUT_DIR"
    adb_cmd exec-out screencap -p > "$OUT_DIR/$1"
    log "captured $OUT_DIR/$1"
}

ui_dump() {
    adb_cmd exec-out uiautomator dump /dev/tty 2>/dev/null || true
}

cleanup() {
    log "cleanup: parking the AVD and stopping OsmAnd"
    adb_cmd shell cmd car_service emulate-driving-state park >/dev/null 2>&1 || true
    adb_cmd shell cmd car_service inject-vhal-event PERF_VEHICLE_SPEED 0 0.0 >/dev/null 2>&1 || true
    adb_cmd shell am force-stop "$PACKAGE" >/dev/null 2>&1 || true
    rm -rf "$TMP_DIR"
}
trap cleanup EXIT

set_parked() {
    adb_cmd shell cmd car_service emulate-driving-state park >/dev/null
    adb_cmd shell cmd car_service inject-vhal-event PERF_VEHICLE_SPEED 0 0.0 >/dev/null
    sleep 1
}

set_driving() {
    adb_cmd shell cmd car_service emulate-driving-state drive >/dev/null
    adb_cmd shell cmd car_service inject-vhal-event PERF_VEHICLE_SPEED 0 10.0 >/dev/null
    sleep 1
}

mkdir -p "$OUT_DIR"
log "serial=$SERIAL expected_product=$PRODUCT"
actual_product=$(adb_cmd shell getprop ro.product.name | tr -d '\r')
if [ "$actual_product" != "$PRODUCT" ]; then
    fail "wrong product: expected $PRODUCT, got $actual_product"
    exit 1
fi
pass "Caramel AAOS product is active ($actual_product)"

adb_cmd shell pm grant --user 10 com.android.car.libraries.templates.host \
        android.permission.RECORD_AUDIO >/dev/null 2>&1 || true
set_parked

launch_map() {
    adb_cmd shell am force-stop "$PACKAGE" >/dev/null
    adb_cmd shell am start -W -n "$ACTIVITY" >/dev/null
    sleep 2
}

launch_map
capture map-baseline.png
adb_cmd shell input tap 1040 96
sleep 1
capture search-parked.png
ime_state=$(adb_cmd shell dumpsys input_method | tr -d '\r')
if printf '%s\n' "$ime_state" | rg -q 'mInputShown=true'; then
    pass "parked map search opens the keyboard without a second tap"
else
    fail "parked map search requires a second tap before showing the keyboard"
fi

log "checking the host-drawn search microphone action"
adb_cmd logcat -c
adb_cmd shell input tap 1030 88
sleep 2
capture search-mic.png
voice_log=$(adb_cmd logcat -d -v brief)
if printf '%s\n' "$voice_log" | rg -q 'CaramelVoice|VoiceInteractionSession'; then
    pass "search microphone starts CaramelVoice"
else
    fail "search microphone only opened local text input; no CaramelVoice session"
fi

log "entering driving state"
set_driving
launch_map
adb_cmd shell input tap 1040 96
sleep 1
adb_cmd shell input tap 800 96
sleep 1
capture search-moving.png
moving_ui=$(ui_dump)
moving_restrictions=$(adb_cmd shell cmd car_service get-current-ux-restrictions 0 | tr -d '\r')
if printf '%s\n' "$moving_ui" | rg -q 'Park to use the keyboard' \
        || printf '%s\n' "$moving_restrictions" | rg -q 'no_keyboard'; then
    pass "moving search blocks manual keyboard entry"
else
    fail "moving search did not present the Park to use the keyboard restriction"
fi

adb_cmd logcat -c
adb_cmd shell input tap 1032 90
sleep 2
capture search-moving-mic.png
moving_voice_log=$(adb_cmd logcat -d -v brief)
if printf '%s\n' "$moving_voice_log" | rg -q '\[DEBUG-VOICE\] search dictation ready|CaramelVoice'; then
    pass "moving search microphone starts app-scoped dictation"
else
    fail "moving search microphone did not start app-scoped dictation"
fi

log "checking settings access while driving"
launch_map
adb_cmd logcat -c
adb_cmd shell input tap 970 96
sleep 1
capture settings-moving.png
settings_log=$(adb_cmd logcat -d -v brief)
if printf '%s\n' "$settings_log" | rg -q 'received template ListTemplate'; then
    fail "moving state still opens the settings ListTemplate"
else
    pass "moving state does not open the settings ListTemplate"
fi

log "checking global push-to-talk while driving"
adb_cmd logcat -c
adb_cmd shell cmd car_service inject-key -t 200 231 >/dev/null
sleep 2
ptt_log=$(adb_cmd logcat -d -v brief)
if printf '%s\n' "$ptt_log" | rg -q 'CaramelVoice|VoiceInteractionSession'; then
    pass "global push-to-talk reaches CaramelVoice while driving"
else
    fail "global push-to-talk did not reach CaramelVoice while driving"
fi

log "checking rotary input path"
adb_cmd shell cmd car_service inject-rotary -d 0 -i 10 -c true >/dev/null 2>&1
if [ $? -eq 0 ]; then
    pass "AAOS accepted a rotary event"
else
    fail "AAOS rejected the rotary event"
fi

if [ "$failures" -ne 0 ]; then
    printf '%s\n' "Baseline audit found $failures deficiency/deficiencies."
    exit 1
fi
printf '%s\n' "PASS: OsmAnd driving-safety audit completed"
