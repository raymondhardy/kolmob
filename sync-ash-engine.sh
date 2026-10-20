#!/bin/bash
# sync-ash-engine.sh
# This script synchronizes the ASH engine from the desktop KoLmafia repository
# to the ash-engine module in this Android project.
#
# Requirements:
# - The desktop KoLmafia repo must be available at ../kolmafia (relative to this script)
# - This script should be run from the root of the Kolmob project

set -e

echo "=== Syncing ASH Engine from Kolmafia desktop repo ==="

# Paths
DESKTOP_REPO="../kolmafia"
ASH_ENGINE_DIR="ash-engine/src/main/java/net/sourceforge/kolmafia"

# Check if desktop repo exists
if [ ! -d "$DESKTOP_REPO" ]; then
    echo "ERROR: Desktop KoLmafia repository not found at $DESKTOP_REPO"
    echo "Please ensure the desktop KoLmafia repo is cloned at ../kolmafia"
    exit 1
fi

# Copy the textui package (which contains the ASH engine)
echo "Copying textui package..."
mkdir -p "$ASH_ENGINE_DIR"
cp -r "$DESKTOP_REPO/src/main/java/net/sourceforge/kolmafia/textui" "$ASH_ENGINE_DIR/"

# Copy utilities that are needed by the ASH engine
echo "Copying utilities package..."
mkdir -p "$ASH_ENGINE_DIR/../utilities"
cp "$DESKTOP_REPO/src/main/java/net/sourceforge/kolmafia/utilities/NullStream.java" "$ASH_ENGINE_DIR/../utilities/"
cp "$DESKTOP_REPO/src/main/java/net/sourceforge/kolmafia/utilities/CharacterEntities.java" "$ASH_ENGINE_DIR/../utilities/"
cp "$DESKTOP_REPO/src/main/java/net/sourceforge/kolmafia/utilities/StringUtilities.java" "$ASH_ENGINE_DIR/../utilities/"
cp "$DESKTOP_REPO/src/main/java/net/sourceforge/kolmafia/utilities/ByteArrayStream.java" "$ASH_ENGINE_DIR/../utilities/"
cp "$DESKTOP_REPO/src/main/java/net/sourceforge/kolmafia/utilities/FileUtilities.java" "$ASH_ENGINE_DIR/../utilities/"
cp "$DESKTOP_REPO/src/main/java/net/sourceforge/kolmafia/utilities/LogStream.java" "$ASH_ENGINE_DIR/../utilities/"
cp "$DESKTOP_REPO/src/main/java/net/sourceforge/kolmafia/utilities/HTMLParserUtils.java" "$ASH_ENGINE_DIR/../utilities/"
cp "$DESKTOP_REPO/src/main/java/net/sourceforge/kolmafia/utilities/WikiUtilities.java" "$ASH_ENGINE_DIR/../utilities/"
cp "$DESKTOP_REPO/src/main/java/net/sourceforge/kolmafia/utilities/ChoiceUtilities.java" "$ASH_ENGINE_DIR/../utilities/"
cp "$DESKTOP_REPO/src/main/java/net/sourceforge/kolmafia/utilities/InputFieldUtilities.java" "$ASH_ENGINE_DIR/../utilities/"
cp "$DESKTOP_REPO/src/main/java/net/sourceforge/kolmafia/utilities/Statics.java" "$ASH_ENGINE_DIR/../utilities/"

# Show summary
echo ""
echo "=== Sync completed ==="
echo "Copied textui package and required utilities"
echo ""
echo "Next steps:"
echo "1. Run './gradlew :ash-engine:build' to verify the build"
echo "2. Update the app module to use the ash-engine module as a dependency if not already done"
echo "3. Remember to give credit to KoLmafia as per the BSD 3-clause license"
echo ""
echo "License: BSD 3-Clause (see LICENSE.md in desktop KoLmafia repo)"
echo "Copyright (c) 2005-2021, KoLmafia development team"