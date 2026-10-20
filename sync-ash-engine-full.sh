#!/bin/bash
# sync-ash-engine.sh
# Synchronizes the KoLmafia core libraries from the desktop repo to the ash-engine module
# for use in the Android app.

set -e

DESKTOP_REPO="${DESKTOP_REPO:-$HOME/workspace/kolmafia}"
ASH_ENGINE_DIR="${ASH_ENGINE_DIR:-$(cd "$(dirname "$0")" && pwd)/ash-engine/src/main/java}"

echo "=== Syncing KoLmafia core libraries to ash-engine module ==="

# Core KoLmafia packages needed by the ASH engine and Android app
PACKAGES=(
    "net/sourceforge/kolmafia"
    "net/sourceforge/kolmafia/persistence"
    "net/sourceforge/kolmafia/request"
    "net/sourceforge/kolmafia/request/coinmaster"
    "net/sourceforge/kolmafia/request/concoction"
    "net/sourceforge/kolmafia/request/concoction/shop"
    "net/sourceforge/kolmafia/session"
    "net/sourceforge/kolmafia/chat"
    "net/sourceforge/kolmafia/combat"
    "net/sourceforge/kolmafia/equipment"
    "net/sourceforge/kolmafia/listener"
    "net/sourceforge/kolmafia/maximizer"
    "net/sourceforge/kolmafia/modifiers"
    "net/sourceforge/kolmafia/moods"
    "net/sourceforge/kolmafia/objectpool"
    "net/sourceforge/kolmafia/preferences"
    "net/sourceforge/kolmafia/textui"
    "net/sourceforge/kolmafia/textui/parsetree"
    "net/sourceforge/kolmafia/utilities"
    "net/sourceforge/kolmafia/webui"
    "net/sourceforge/kolmafia/swingui"
    "net/sourceforge/kolmafia/swingui/button"
    "net/sourceforge/kolmafia/swingui/listener"
    "net/sourceforge/kolmafia/swingui/menu"
    "net/sourceforge/kolmafia/swingui/panel"
    "net/sourceforge/kolmafia/swingui/table"
    "net/sourceforge/kolmafia/swingui/widget"
    "net/sourceforge/kolmafia/textui/command"
    "net/sourceforge/kolmafia/textui/javascript"
    "net/sourceforge/kolmafia/textui/renderer"
    "net/java/dev/spellcast/utilities"
    "ca/bcit/geekkit"
    "com/alibaba/fastjson2"
)

echo "Creating package directories..."
for pkg in "${PACKAGES[@]}"; do
    mkdir -p "$ASH_ENGINE_DIR/$pkg"
done

echo "Copying core KoLmafia classes..."
rsync -av --exclude='*Test*.java' --exclude='*Test*.class' \
    "$DESKTOP_REPO/src/main/java/net/sourceforge/kolmafia/" \
    "$ASH_ENGINE_DIR/net/sourceforge/kolmafia/"

echo "Copying spellcast utilities..."
rsync -av "$DESKTOP_REPO/src/main/java/net/java/dev/spellcast/utilities/" \
    "$ASH_ENGINE_DIR/net/java/dev/spellcast/utilities/"

echo "Copying geekkit..."
rsync -av "$DESKTOP_REPO/src/main/java/ca/bcit/geekkit/" \
    "$ASH_ENGINE_DIR/ca/bcit/geekkit/"

echo "Sync complete!"
echo ""
echo "Next steps:"
echo "1. Review and fix any Android-incompatible code (Swing/AWT imports in non-UI classes)"
echo "2. Update ash-engine/build.gradle.kts with required dependencies"
echo "3. Build ash-engine module: ./gradlew :ash-engine:build"
echo "4. Use in Android app via implementation(project(':ash-engine'))"