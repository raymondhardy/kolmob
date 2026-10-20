#!/usr/bin/env python3
"""Checks that the bottom navigation bar is visible on every screen reachable from More > Chat/Tools/Settings.

Needs: adb on a device with Kolmob logged in (tools/dev-login.sh) and /tmp/ui.py (the small adb UI driver).
Usage: python3 -u tools/navcheck.py [tool title ...]
A screen FAILS when an app window taller than half the display ends below the top of the nav bar.
Between checks it dismisses whatever is open by pressing Back only while an overlay window exists, so it never
backs out of the app itself.
"""
import os, re, sys, time
sys.path.insert(0, __import__('os').path.dirname(__import__('os').path.abspath(__file__)))
import ui

NAV_TOP = 2130
MORE = ('972', '2200')   # the More button in the bottom nav (1080x2340 display)
REG = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'app/src/main/java/com/example/kolmafiaandroid/tools/ToolRegistry.kt')).read()
TITLES = re.findall(r'(?<!sub)title = "([^"]+)"', REG)


def windows():
    out = ui.sh('dumpsys', 'window', 'windows')
    res = []
    for blk in re.split(r'\n  Window #', out):
        if 'com.example.kolmafiaandroid' not in blk.split('\n')[0]:
            continue
        if 'isVisible=true' not in blk:
            continue
        ty = re.search(r'ty=(\w+)', blk)
        fr = re.search(r'frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]', blk)
        if ty and fr:
            res.append((ty.group(1), list(map(int, fr.groups()))))
    return res


def overlays():
    return [w for w in windows() if w[0] != 'BASE_APPLICATION']


def covers_nav(ws):
    bad = []
    for ty, f in ws:
        if ty == 'BASE_APPLICATION':
            continue
        if f[3] - f[1] > 0.5 * 2340 and f[3] > NAV_TOP + 2:
            bad.append((ty, f))
    return bad


def reset():
    """Close every dialog/sheet/full-screen screen; True when only the main screen is left."""
    for _ in range(8):
        if not ui.ensure_app():
            time.sleep(2)
        if not overlays():
            return True
        ui.sh('input', 'keyevent', '4')
        time.sleep(1.0)
    return not overlays()


def tap_text(t, scrolls=12):
    for _ in range(scrolls):
        if ui.tap(lambda c, tt, d, b: (tt == t or tt.startswith(t + ' ')) and b[1] > 150 and b[3] < NAV_TOP):
            return True
        ui.sh('input', 'swipe', '540', '1700', '540', '700', '300')
        time.sleep(0.8)
    return False


def open_more():
    ui.sh('input', 'tap', *MORE)
    time.sleep(1.5)
    return True


def more_then(item):
    def f():
        open_more()
        return tap_text(item, 1)
    return f


def tool(title):
    def f():
        open_more()
        if not tap_text('Tools', 1):
            return False
        time.sleep(2)
        return tap_text(title)
    return f


results = []


def check(name, opener):
    ok = False
    for attempt in range(3):
        if not reset():
            print(name, 'STUCK: could not close the previous screen', flush=True)
            break
        ok = opener()
        if ok:
            break
    time.sleep(2.5)
    ws = windows()
    bad = covers_nav(ws) if ok else []
    status = 'OPEN' if ok else 'NOT FOUND'
    results.append((name, status, bad))
    print(name, status, 'COVERS NAV ' + str(bad) if bad else ('nav ok' if ok else ''), flush=True)


def main():
    which = sys.argv[1:]
    if not which:
        check('More>Chat', more_then('Chat'))
        check('More>Settings', more_then('Settings'))
    for t in TITLES:
        if which and t not in which:
            continue
        check('Tool>' + t, tool(t))
    reset()
    fails = [r[0] for r in results if r[2]]
    missing = [r[0] for r in results if r[1] != 'OPEN']
    print('FAILURES:', fails)
    print('COULD NOT OPEN:', missing)


main()
