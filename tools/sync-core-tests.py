#!/usr/bin/env python3
"""Gives Kolmob's own engine tests (core-tests) the upstream test helpers they build on.

Kolmob does not copy or patch upstream's tests: those run upstream. Our tests (tracked in git under core-tests) use
upstream's test library (internal.helpers.Player, Cleanups, FakeHttpClientBuilder, ...), so that library and the data
files it reads are copied here unchanged (plus the few test-support classes it uses, SUPPORT). The one helper that reaches into the desktop GUI (internal/helpers/
Maximizer.java, which uses MaximizerFrame) is left out; our tests do not use it.

Usage: tools/sync-core-tests.py [path-to-kolmafia-checkout]   (run by tools/sync-upstream.sh)
"""
import os, shutil, subprocess, sys

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
UP = sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser('~/workspace/kolmafia')
DST = os.path.join(HERE, 'core-tests')
SKIP = {'internal/helpers/Maximizer.java'}
# Test-support classes outside internal/ that the helpers use
SUPPORT = ['net/sourceforge/kolmafia/utilities/TestStatics.java']


def tracked():
    out = subprocess.run(['git', '-C', HERE, 'ls-files', 'core-tests/src', 'core-tests/root'],
                         capture_output=True, text=True, check=True).stdout
    return set(out.split())


def clear_untracked(dst, keep):
    """Removes everything under dst that git does not track (earlier copies of upstream files)."""
    for root, _, files in os.walk(dst):
        for f in files:
            path = os.path.join(root, f)
            if os.path.relpath(path, HERE) not in keep:
                os.remove(path)


def main():
    src = os.path.join(UP, 'src/test/java')
    if not os.path.isdir(src):
        sys.exit(f'upstream tests not found at {UP}')
    keep = tracked()
    java = os.path.join(DST, 'src/test/java')
    os.makedirs(java, exist_ok=True)
    clear_untracked(java, keep)
    shutil.copytree(os.path.join(src, 'internal'), os.path.join(java, 'internal'), dirs_exist_ok=True,
                    ignore=lambda d, names: [n for n in names
                                             if os.path.relpath(os.path.join(d, n), src) in SKIP])
    for rel in SUPPORT:
        os.makedirs(os.path.dirname(os.path.join(java, rel)), exist_ok=True)
        shutil.copy2(os.path.join(src, rel), os.path.join(java, rel))
    for sub, dst in (('src/test/resources', 'src/test/resources'), ('src/test/root', 'root')):
        if os.path.isdir(os.path.join(UP, sub)):
            os.makedirs(os.path.join(DST, dst), exist_ok=True)
            clear_untracked(os.path.join(DST, dst), keep)
            shutil.copytree(os.path.join(UP, sub), os.path.join(DST, dst), dirs_exist_ok=True)
    n = sum(len(f) for _, _, f in os.walk(os.path.join(java, 'internal')))
    print(f'core-tests: {n} upstream helper files (unchanged); Kolmob tests: '
          f'{len([k for k in keep if k.endswith(".java")])}')


if __name__ == '__main__':
    main()
