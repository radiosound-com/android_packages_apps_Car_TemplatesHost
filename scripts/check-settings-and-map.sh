#!/bin/sh
# Copyright 2026 Radio Sound, Inc.
# Licensed under the Apache License, Version 2.0.

set -eu

# End-to-end checks for the two interactive surfaces on the 1080x600 AAOS
# landscape emulator profile: settings ListTemplate chrome and map gestures.
ADB=${ADB:-adb}
DISPLAY_ID=${DISPLAY_ID:-4619827259835644672}
PACKAGE=${PACKAGE:-net.osmand.dev}
INPUT_SOURCE=${INPUT_SOURCE:-mouse}
COMPONENT="$PACKAGE/androidx.car.app.activity.CarAppActivity"
tmp_dir=$(mktemp -d "${TMPDIR:-/tmp}/caramel-vanilla-host-interaction.XXXXXX")
trap 'rm -rf "$tmp_dir"' EXIT

"$ADB" logcat -c

capture() {
    "$ADB" exec-out screencap -p -d "$DISPLAY_ID" > "$2"
}

swipe() {
    "$ADB" shell input "$INPUT_SOURCE" swipe "$@"
}

start_map() {
    "$ADB" shell am force-stop "$PACKAGE"
    "$ADB" shell am start -W -n "$COMPONENT" >/dev/null
    sleep 12
    # Car App resumes its last screen. The map screen has no back affordance
    # at this coordinate, while the persisted Settings screen does.
    "$ADB" shell input tap 40 87
    sleep 2
}

start_map
capture /sdcard/caramel-vanilla-map-before.png "$tmp_dir/map-before.png"
swipe 700 300 850 300 500
sleep 1
capture /sdcard/caramel-vanilla-map-after-right.png "$tmp_dir/map-after-right.png"

start_map
capture /sdcard/caramel-vanilla-map-before-left.png "$tmp_dir/map-before-left.png"
swipe 700 300 550 300 100
sleep 1
capture /sdcard/caramel-vanilla-map-after-left.png "$tmp_dir/map-after-left.png"

start_map
capture /sdcard/caramel-vanilla-map-before-reversal.png "$tmp_dir/map-before-reversal.png"
swipe 700 300 600 300 100
sleep 1
capture /sdcard/caramel-vanilla-map-after-first-reversal.png "$tmp_dir/map-after-first-reversal.png"
swipe 600 300 800 300 500
sleep 1
capture /sdcard/caramel-vanilla-map-after-reversal.png "$tmp_dir/map-after-reversal.png"

"$ADB" logcat -c
swipe 700 300 550 300 5000
sleep 1
slow_input_ms=$("$ADB" logcat -d -v threadtime \
    | rg 'InputDispatcher: Embedded\{.*\} spent [0-9]{4,}ms processing MotionEvent' \
    | sed -E 's/.* spent ([0-9]+)ms.*/\1/' | sort -nr | head -1 || true)
slow_input_ms=${slow_input_ms:-0}

start_map
capture /sdcard/caramel-vanilla-map-before-down.png "$tmp_dir/map-before-down.png"
swipe 850 300 850 450 500
sleep 1
capture /sdcard/caramel-vanilla-map-after-down.png "$tmp_dir/map-after-down.png"

start_map
"$ADB" shell input tap 970 98
sleep 2
capture /sdcard/caramel-vanilla-settings.png "$tmp_dir/settings.png"
surface_failures=$("$ADB" logcat -d -v brief | rg -c \
    'Surface lost, forcing relayout|Key was rejected by service|createGraphicBuffer failed' || echo 0)

python3 - "$tmp_dir/map-before.png" "$tmp_dir/map-after-right.png" \
    "$tmp_dir/map-before-left.png" "$tmp_dir/map-after-left.png" \
    "$tmp_dir/map-before-reversal.png" "$tmp_dir/map-after-first-reversal.png" \
    "$tmp_dir/map-after-reversal.png" "$tmp_dir/map-before-down.png" \
    "$tmp_dir/map-after-down.png" "$tmp_dir/settings.png" \
    "$surface_failures" "$slow_input_ms" <<'PY'
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
                raise SystemExit("FAIL: expected 8-bit RGBA screenshot")
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


def pixel(rows, x, y):
    return tuple(rows[y][x * 4:x * 4 + 4])


def location_marker_center_x(rows):
    points = set()
    for y in range(70, 530):
        for x in range(405, 1070):
            if pixel(rows, x, y)[:3] == (35, 123, 255):
                points.add((x, y))
    components = []
    while points:
        stack = [points.pop()]
        component = []
        while stack:
            x, y = stack.pop()
            component.append((x, y))
            for neighbor in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
                if neighbor in points:
                    points.remove(neighbor)
                    stack.append(neighbor)
        width = max(x for x, _ in component) - min(x for x, _ in component) + 1
        height = max(y for _, y in component) - min(y for _, y in component) + 1
        if len(component) >= 100 and 15 <= width <= 35 and 15 <= height <= 35:
            components.append(component)
    if not components:
        return None
    component = max(components, key=len)
    return (min(x for x, _ in component) + max(x for x, _ in component)) // 2


def location_marker_center(rows):
    """Find OsmAnd's blue location marker in the stable center column."""
    ys = []
    for y in range(70, 530):
        matching = 0
        for x in range(500, 580):
            if pixel(rows, x, y)[:3] == (35, 123, 255):
                matching += 1
        if matching >= 5:
            ys.append(y)
    if not ys:
        return None
    return (min(ys) + max(ys)) // 2


def near(value, expected, tolerance):
    return all(abs(value[i] - expected[i]) <= tolerance for i in range(3))


def bright_neutral(value):
    return min(value[:3]) > 140 and max(value[:3]) - min(value[:3]) < 40


def shift_error(reference, moved, shift_x, shift_y):
    """Compare map content after a candidate translation.

    A negative shift means the rendered map content moved left/up; a positive
    shift means it moved right/down, matching a paper map dragged under a
    finger.
    """
    _, _, reference_rows = reference
    _, _, moved_rows = moved
    total = count = 0
    for y in range(70, 530, 4):
        reference_y = y - shift_y
        if not 70 <= reference_y < 530:
            continue
        for x in range(405, 900, 4):
            reference_x = x - shift_x
            if not 405 <= reference_x < 900:
                continue
            moved_pixel = moved_rows[y]
            reference_pixel = reference_rows[reference_y]
            moved_offset = x * 4
            reference_offset = reference_x * 4
            total += sum(abs(moved_pixel[moved_offset + channel]
                             - reference_pixel[reference_offset + channel])
                         for channel in range(3))
            count += 1
    return total / count if count else float("inf")


before_right = decode_png(sys.argv[1])
after_right = decode_png(sys.argv[2])
before_left = decode_png(sys.argv[3])
after_left = decode_png(sys.argv[4])
before_reversal = decode_png(sys.argv[5])
after_first_reversal = decode_png(sys.argv[6])
after_reversal = decode_png(sys.argv[7])
before_down = decode_png(sys.argv[8])
after_down = decode_png(sys.argv[9])
settings = decode_png(sys.argv[10])
surface_failures = int(sys.argv[11])
slow_input_ms = int(sys.argv[12])
if any(image[:2] != (1080, 600) for image in
       (before_right, after_right, before_left, after_left, before_down,
        after_down, before_reversal, after_first_reversal, after_reversal,
        settings)):
    raise SystemExit("FAIL: expected 1080x600 screenshots")

_, _, before_rows = before_right
_, _, after_rows = after_right
_, _, settings_rows = settings

map_active = 0
map_diffs = 0
for y in range(100, 500):
    for x in range(600, 900):
        old = pixel(before_rows, x, y)[:3]
        new = pixel(after_rows, x, y)[:3]
        if max(old) > 40:
            map_active += 1
        if old != new:
            map_diffs += 1

right_scores = [(shift_error(before_right, after_right, shift, 0), shift)
                for shift in range(-500, 501, 10)]
left_scores = [(shift_error(before_left, after_left, shift, 0), shift)
               for shift in range(-500, 501, 10)]
down_scores = [(shift_error(before_down, after_down, 0, shift), shift)
               for shift in range(-500, 501, 10)]
right_shift = min(right_scores)[1]
left_shift = min(left_scores)[1]
down_shift = min(down_scores)[1]
before_reversal_marker = location_marker_center_x(before_reversal[2])
after_first_reversal_marker = location_marker_center_x(after_first_reversal[2])
after_reversal_marker = location_marker_center_x(after_reversal[2])
before_down_marker = location_marker_center(before_down[2])
after_down_marker = location_marker_center(after_down[2])
history_pixels = [(x, y) for y in range(205, 244) for x in range(20, 65)
                  if bright_neutral(pixel(before_rows, x, y))]
history_bounds = (
    min(x for x, _ in history_pixels), max(x for x, _ in history_pixels),
    min(y for _, y in history_pixels), max(y for _, y in history_pixels),
) if history_pixels else (999, -1, 999, -1)

checks = {
    "map is rendered before gesture": map_active > 10000,
    "map responds to a swipe": map_diffs > 1000,
    "rightward finger drag moves map left": right_shift < -20,
    "leftward finger drag moves map right after release": left_shift > 20,
    "reversing drag cancels previous inertia": before_reversal_marker is not None
        and after_first_reversal_marker is not None
        and after_reversal_marker is not None
        and after_first_reversal_marker > before_reversal_marker + 20
        and after_reversal_marker < after_first_reversal_marker - 20,
    "downward finger drag moves map up": before_down_marker is not None
        and after_down_marker is not None
        and after_down_marker < before_down_marker - 20,
    "history icon has stock circular-arrow geometry": len(history_pixels) > 260
        and history_bounds[0] <= 30 and history_bounds[1] >= 55
        and history_bounds[3] >= 236,
    "settings uses stock dark background": near(pixel(settings_rows, 50, 100), (19, 19, 19, 255), 12),
    "settings toolbar back affordance exists": max(pixel(settings_rows, 40, 87)[:3]) > 100,
    "settings switch is rendered": max(pixel(settings_rows, 994, 167)[:3]) > 50
        and max(pixel(settings_rows, 994, 167)[:3]) - min(pixel(settings_rows, 994, 167)[:3]) < 12,
    "host has no rejected-surface loop": surface_failures < 10,
    "map drag does not block input dispatch": slow_input_ms < 1000,
}
failed = [name for name, passed in checks.items() if not passed]
if failed:
    for name in failed:
        print("FAIL:", name)
    print("map_active=", map_active, "map_diffs=", map_diffs,
          "right_shift=", right_shift, "left_shift=", left_shift,
          "down_shift=", down_shift,
          "before_reversal_marker=", before_reversal_marker,
          "after_first_reversal_marker=", after_first_reversal_marker,
          "after_reversal_marker=", after_reversal_marker,
          "before_down_marker=", before_down_marker,
          "after_down_marker=", after_down_marker,
          "surface_failures=", surface_failures,
          "slow_input_ms=", slow_input_ms,
          "history_pixels=", len(history_pixels), "history_bounds=", history_bounds)
    raise SystemExit(1)
print("PASS: settings layout and map gesture behavior match the stock host profile")
PY
