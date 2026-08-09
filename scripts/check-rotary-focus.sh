#!/bin/sh
# Copyright 2026 Radio Sound, Inc.
# Licensed under the Apache License, Version 2.0.

set -eu

ADB=${ADB:-adb}
DISPLAY_ID=${DISPLAY_ID:-4619827259835644672}
HOST=${HOST:-com.android.car.libraries.templates.host}
CONFORMANCE=com.android.car.libraries.templates.conformance
COMPONENT="$CONFORMANCE/androidx.car.app.activity.CarAppActivity"
TMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/caramel-vanilla-rotary.XXXXXX")
trap 'rm -rf "$TMP_DIR"' EXIT

capture() {
    "$ADB" exec-out screencap -p -d "$DISPLAY_ID" > "$2"
}

if [ "$HOST" = com.google.android.apps.automotive.templates.host ]; then
    "$ADB" shell pm disable-user --user 10 com.android.car.libraries.templates.host >/dev/null 2>&1 || true
    "$ADB" shell pm enable --user 10 "$HOST" >/dev/null
else
    "$ADB" shell pm disable-user --user 10 com.google.android.apps.automotive.templates.host >/dev/null 2>&1 || true
    "$ADB" shell pm enable --user 10 "$HOST" >/dev/null
fi

"$ADB" shell am force-stop "$CONFORMANCE"
"$ADB" shell am start -W -n "$COMPONENT" --es mode interaction-grid >/dev/null
sleep 2
capture "$TMP_DIR/before.png" "$TMP_DIR/before.png"
"$ADB" shell input keyevent KEYCODE_DPAD_DOWN
sleep 1
capture "$TMP_DIR/focused.png" "$TMP_DIR/focused.png"
"$ADB" shell input keyevent KEYCODE_DPAD_CENTER
sleep 2
capture "$TMP_DIR/activated.png" "$TMP_DIR/activated.png"

"$ADB" shell am force-stop "$CONFORMANCE"
"$ADB" shell am start -W -n "$COMPONENT" --es mode interaction-grid >/dev/null
sleep 2
capture "$TMP_DIR/encoder-before.png" "$TMP_DIR/encoder-before.png"
"$ADB" shell input scroll --axis VSCROLL,-1
sleep 1
capture "$TMP_DIR/encoder-focused.png" "$TMP_DIR/encoder-focused.png"
"$ADB" shell input keyevent KEYCODE_DPAD_CENTER
sleep 2
capture "$TMP_DIR/encoder-activated.png" "$TMP_DIR/encoder-activated.png"

python3 - "$TMP_DIR" <<'PY'
import os
import struct
import sys
import zlib


def decode_png(path):
    data = open(path, "rb").read()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise SystemExit(f"FAIL: invalid PNG: {path}")
    pos = 8
    compressed = bytearray()
    width = height = None
    while pos < len(data):
        size = struct.unpack(">I", data[pos:pos + 4])[0]
        kind = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + size]
        pos += size + 12
        if kind == b"IHDR":
            width, height, depth, color_type, _, _, _ = struct.unpack(
                ">IIBBBBB", chunk
            )
            if depth != 8 or color_type != 6:
                raise SystemExit("FAIL: expected RGBA screenshots")
        elif kind == b"IDAT":
            compressed.extend(chunk)
        elif kind == b"IEND":
            break
    raw = zlib.decompress(compressed)
    stride = width * 4
    rows = []
    previous = bytearray(stride)
    pos = 0
    for _ in range(height):
        filter_type = raw[pos]
        pos += 1
        current = bytearray(raw[pos:pos + stride])
        pos += stride
        for i in range(stride):
            left = current[i - 4] if i >= 4 else 0
            above = previous[i]
            upper_left = previous[i - 4] if i >= 4 else 0
            if filter_type == 1:
                current[i] = (current[i] + left) & 255
            elif filter_type == 2:
                current[i] = (current[i] + above) & 255
            elif filter_type == 3:
                current[i] = (current[i] + (left + above) // 2) & 255
            elif filter_type == 4:
                estimate = left + above - upper_left
                pa = abs(estimate - left)
                pb = abs(estimate - above)
                pc = abs(estimate - upper_left)
                predictor = left if pa <= pb and pa <= pc else above if pb <= pc else upper_left
                current[i] = (current[i] + predictor) & 255
            elif filter_type != 0:
                raise SystemExit("FAIL: unsupported PNG filter")
        rows.append(current)
        previous = current
    return width, height, rows


def pixel(image, x, y):
    return image[2][y][x * 4:x * 4 + 4]


def difference(a, b, left, top, right, bottom):
    total = 0
    for y in range(top, bottom, 3):
        for x in range(left, right, 3):
            pa = pixel(a, x, y)
            pb = pixel(b, x, y)
            total += sum(abs(pa[c] - pb[c]) for c in range(3))
    return total


before = decode_png(os.path.join(sys.argv[1], "before.png"))
focused = decode_png(os.path.join(sys.argv[1], "focused.png"))
activated = decode_png(os.path.join(sys.argv[1], "activated.png"))
encoder_before = decode_png(os.path.join(sys.argv[1], "encoder-before.png"))
encoder_focused = decode_png(os.path.join(sys.argv[1], "encoder-focused.png"))
encoder_activated = decode_png(os.path.join(sys.argv[1], "encoder-activated.png"))
if any(image[:2] != before[:2] for image in (
        focused, activated, encoder_before, encoder_focused, encoder_activated)):
    raise SystemExit("FAIL: rotary screenshot dimensions differ")

checks = {
    "directional input changes focus rendering":
        difference(before, focused, 40, 100, before[0] - 40, before[1] - 100) > 1000,
    "center activates focused grid item":
        difference(focused, activated, 0, 70, focused[0], min(focused[1], 520)) > 1000,
    "rotary encoder changes focus rendering":
        difference(encoder_before, encoder_focused, 40, 100,
                   encoder_before[0] - 40, encoder_before[1] - 100) > 1000,
    "rotary encoder center activates focused item":
        difference(encoder_focused, encoder_activated, 0, 70,
                   encoder_focused[0], min(encoder_focused[1], 520)) > 1000,
}
for name, passed in checks.items():
    print(("PASS: " if passed else "FAIL: ") + name)
if not all(checks.values()):
    raise SystemExit(1)
print("PASS: rotary focus and activation sequence completed")
PY
