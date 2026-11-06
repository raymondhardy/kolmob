#!/usr/bin/env bash
# Sync the headless KoLmafia core from the upstream checkout into :kolmafia-core.
# Usage: tools/sync-upstream.sh [path-to-kolmafia-checkout]
# Upstream sources are copied verbatim (package names/paths unchanged). Android-specific
# changes live in :compat (Swing/AWT shims) and patches/ (if any), never as hand edits here.
set -euo pipefail
UP="${1:-$HOME/workspace/kolmafia}"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
DST="$HERE/kolmafia-core/src/main"
[ -d "$UP/src/main/java" ] || { echo "upstream not found at $UP"; exit 1; }

rm -rf "$DST/java" "$DST/resources"
mkdir -p "$DST/java" "$DST/resources"

# Java sources: everything except the Swing GUI and Swing-only helper libs. The engine itself needs no Swing/AWT
# (see upstream devdoc/engine-ui-separation.md); the app supplies its own UserInterface (engine KolmobUserInterface).
rsync -a \
  --exclude 'net/sourceforge/kolmafia/swingui/' \
  --exclude 'net/java/dev/spellcast/utilities/ActionPanel.java' --exclude 'net/java/dev/spellcast/utilities/ActionVerifyPanel.java' \
  --exclude 'net/java/dev/spellcast/utilities/JComponentUtilities.java' --exclude 'net/java/dev/spellcast/utilities/LicenseDisplay.java' \
  --exclude 'net/java/dev/spellcast/utilities/PanelList.java' --exclude 'net/java/dev/spellcast/utilities/PanelListCell.java' \
  --exclude 'ca/' --exclude 'com/informit/' --exclude 'com/sun/' --exclude 'darrylbu/' \
  "$UP/src/main/java/" "$DST/java/"

# Android overrides: SVN/Git script managers (SVNKit/JGit are not usable on Android) and Kolmob's own engine files.
rm -rf "$DST/java/net/sourceforge/kolmafia/scripts/svn"
rsync -a "$HERE/kolmafia-overrides/java/" "$DST/java/"

# Data files and bundled resources (no desktop icons).
rsync -a --exclude 'icons/' --exclude 'META-INF/services/net.sourceforge.kolmafia.ui.UserInterface' "$UP/src/main/resources/" "$DST/resources/"

python3 "$HERE/tools/apply_patches.py"
python3 "$HERE/tools/gen_git_override.py"
python3 "$HERE/tools/sync-core-tests.py" "$UP"

echo "upstream commit: $(git -C "$UP" rev-parse --short HEAD 2>/dev/null || echo unknown)" > "$HERE/kolmafia-core/UPSTREAM_VERSION"
echo "java files: $(find "$DST/java" -name '*.java' | wc -l)"
