"""Tiny adb UI driver used by tools/navcheck.py (tap by visible text, dump the screen, keep the app focused)."""
import re, subprocess, time, html, os

ADB = os.path.expanduser('~/Android/Sdk/platform-tools/adb')
PKG = 'com.example.kolmafiaandroid'


def run(*a):
    return subprocess.run([ADB, *a], capture_output=True, text=True, timeout=60)


def sh(*a):
    return run('shell', *a).stdout


def focus():
    out = sh('dumpsys', 'window')
    m = re.search(r'mCurrentFocus=Window\{[^ ]+ u0 ([^ }]+)', out)
    return m.group(1) if m else ''


def ensure_app():
    """Never tap unless Kolmob is the focused app (a stray tap could hit the launcher or Android Settings)."""
    f = focus()
    if not f.startswith(PKG):
        sh('input', 'keyevent', '3'); time.sleep(1)
        sh('am', 'start', '-n', PKG + '/.MainActivity'); time.sleep(5)
        return False
    return True


def items():
    sh('uiautomator', 'dump', '/sdcard/ui.xml')
    x = sh('cat', '/sdcard/ui.xml')
    out = []
    for m in re.finditer(r'<node[^>]*?>', x):
        n = m.group(0)
        t = re.search(r' text="([^"]*)"', n)
        d = re.search(r'content-desc="([^"]*)"', n)
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        c = re.search(r'class="([^"]*)"', n)
        if b:
            out.append((c.group(1).split('.')[-1], html.unescape(t.group(1)) if t else '',
                        html.unescape(d.group(1)) if d else '', list(map(int, b.groups()))))
    return out


def tap(pred):
    if not ensure_app():
        return False
    for c, t, d, b in items():
        if pred(c, t, d, b):
            sh('input', 'tap', str((b[0] + b[2]) // 2), str((b[1] + b[3]) // 2))
            return True
    return False
