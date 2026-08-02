#!/bin/sh
set -eu

# Differential interaction smoke test for the stock and Caramel hosts on the
# 1080x600 AAOS emulator. The conformance interaction modes deliberately have
# enough content to exercise touch scrolling, rotary scrolling, text input,
# and click callbacks.
ADB=${ADB:-adb}
DISPLAY_ID=${DISPLAY_ID:-4619827259835644672}
CONFORMANCE=com.android.car.libraries.templates.conformance
CONFORMANCE_COMPONENT="$CONFORMANCE/androidx.car.app.activity.CarAppActivity"
OSMAND=net.osmand.dev
OSMAND_COMPONENT="$OSMAND/androidx.car.app.activity.CarAppActivity"
TMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/caramel-vanilla-interaction.XXXXXX")
trap 'rm -rf "$TMP_DIR"' EXIT

capture() {
    "$ADB" exec-out screencap -p -d "$DISPLAY_ID" > "$2"
}

launch_conformance() {
    mode=$1
    "$ADB" shell am force-stop "$OSMAND"
    "$ADB" shell am force-stop "$CONFORMANCE"
    "$ADB" shell am start -W -n "$CONFORMANCE_COMPONENT" --es mode "$mode" >/dev/null
    sleep 2
}

set_host() {
    host=$1
    if [ "$host" = google ]; then
        "$ADB" shell pm disable-user --user 10 com.android.car.libraries.templates.host >/dev/null
        "$ADB" shell pm enable --user 10 com.google.android.apps.automotive.templates.host >/dev/null
    else
        "$ADB" shell pm disable-user --user 10 com.google.android.apps.automotive.templates.host >/dev/null
        "$ADB" shell pm enable --user 10 com.android.car.libraries.templates.host >/dev/null
    fi
}

run_host() {
    host=$1
    set_host "$host"

    launch_conformance interaction-grid
    capture "$TMP_DIR/grid-$host-before.png" "$TMP_DIR/grid-$host-before.png"
    "$ADB" shell input tap 150 170
    sleep 3
    capture "$TMP_DIR/grid-$host-tap.png" "$TMP_DIR/grid-$host-tap.png"
    "$ADB" shell input scroll --axis VSCROLL,-1
    sleep 1
    capture "$TMP_DIR/grid-$host-rotary.png" "$TMP_DIR/grid-$host-rotary.png"

    launch_conformance interaction-search
    capture "$TMP_DIR/search-$host-before.png" "$TMP_DIR/search-$host-before.png"
    "$ADB" shell input swipe 700 260 700 150 500
    sleep 1
    capture "$TMP_DIR/search-$host-swipe.png" "$TMP_DIR/search-$host-swipe.png"
    "$ADB" shell input tap 200 85
    sleep 1
    "$ADB" shell input text 'hello'
    sleep 1
    capture "$TMP_DIR/search-$host-text.png" "$TMP_DIR/search-$host-text.png"
    "$ADB" shell input tap 180 140
    sleep 1
    capture "$TMP_DIR/search-$host-row.png" "$TMP_DIR/search-$host-row.png"

    "$ADB" shell am force-stop "$OSMAND"
    "$ADB" shell am start -W -n "$OSMAND_COMPONENT" >/dev/null
    sleep 12
    for _ in 1 2 3; do
        "$ADB" shell input tap 970 98
        sleep 0.5
    done
    sleep 1
    capture "$TMP_DIR/settings-$host-before.png" "$TMP_DIR/settings-$host-before.png"
    "$ADB" shell input tap 994 178
    sleep 3
    capture "$TMP_DIR/settings-$host-toggle.png" "$TMP_DIR/settings-$host-toggle.png"
}

run_host google
run_host caramel

python3 - "$TMP_DIR" <<'PY'
import os
import struct
import sys
import zlib


def decode_png(path):
    data = open(path, "rb").read()
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


def region_difference(a, b, left, top, right, bottom):
    total = 0
    for y in range(top, bottom, 3):
        for x in range(left, right, 3):
            pa = pixel(a, x, y)
            pb = pixel(b, x, y)
            total += sum(abs(pa[c] - pb[c]) for c in range(3))
    return total


def bright_count(image, left, top, right, bottom):
    total = 0
    for y in range(top, bottom):
        for x in range(left, right):
            value = pixel(image, x, y)
            if max(value[:3]) > 80 and max(value[:3]) - min(value[:3]) < 50:
                total += 1
    return total


checks = {}
for host in ("google", "caramel"):
    grid_before = decode_png(os.path.join(sys.argv[1], f"grid-{host}-before.png"))
    grid_tap = decode_png(os.path.join(sys.argv[1], f"grid-{host}-tap.png"))
    grid_rotary = decode_png(os.path.join(sys.argv[1], f"grid-{host}-rotary.png"))
    search_before = decode_png(os.path.join(sys.argv[1], f"search-{host}-before.png"))
    search_swipe = decode_png(os.path.join(sys.argv[1], f"search-{host}-swipe.png"))
    search_text = decode_png(os.path.join(sys.argv[1], f"search-{host}-text.png"))
    search_row = decode_png(os.path.join(sys.argv[1], f"search-{host}-row.png"))
    settings_before = decode_png(os.path.join(sys.argv[1], f"settings-{host}-before.png"))
    settings_toggle = decode_png(os.path.join(sys.argv[1], f"settings-{host}-toggle.png"))
    images = [grid_before, grid_tap, grid_rotary, search_before, search_swipe,
              search_text, search_row, settings_before, settings_toggle]
    if any(image[:2] != (1080, 600) for image in images):
        raise SystemExit(f"FAIL: {host} screenshot dimensions")

    checks[f"{host} grid has left scroll affordances"] = (
        bright_count(grid_before, 22, 132, 43, 160) > 8
        and bright_count(grid_before, 22, 480, 43, 510) > 8
    )
    checks[f"{host} grid item tap changes app template"] = (
        region_difference(grid_before, grid_tap, 0, 58, 520, 112) > 1000
    )
    checks[f"{host} rotary scroll moves grid content"] = (
        region_difference(grid_before, grid_rotary, 60, 110, 1020, 500) > 5000
    )
    checks[f"{host} search has left scroll affordances"] = (
        bright_count(search_before, 22, 132, 43, 160) > 8
        and bright_count(search_before, 22, 480, 43, 510) > 8
    )
    checks[f"{host} search swipe moves list"] = (
        region_difference(search_before, search_swipe, 60, 110, 1020, 295) > 1000
    )
    checks[f"{host} text search reaches template"] = (
        region_difference(search_before, search_text, 65, 65, 260, 110) > 500
    )
    checks[f"{host} search row tap changes app template"] = (
        region_difference(search_text, search_row, 60, 115, 500, 205) > 1000
    )
    checks[f"{host} settings toggle changes control"] = (
        region_difference(settings_before, settings_toggle, 950, 145, 1040, 210) > 1000
    )

failed = [name for name, passed in checks.items() if not passed]
for name, passed in checks.items():
    print(("PASS: " if passed else "FAIL: ") + name)
if failed:
    raise SystemExit(1)
print("PASS: stock and Caramel interaction parity sequence completed")
PY
