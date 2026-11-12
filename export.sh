
#!/usr/bin/env bash

set -Eeuo pipefail

# ==========================================
# YUMMY - STAGE 17 DEBUG SOURCE EXPORTER
# ==========================================

ROOT="$(git rev-parse --show-toplevel 2>/dev/null)" || {
    echo "ERROR: Jalankan script dari dalam repository Git Yummy."
    exit 1
}

cd "$ROOT"

OUTPUT="$ROOT/Yummy_Stage17_Debug_Source.zip"
TEMP="$(mktemp -d)"
trap 'rm -rf "$TEMP"' EXIT

STAGE="$TEMP/source"
mkdir -p "$STAGE"

echo "=========================================="
echo " YUMMY STAGE 17 SOURCE EXPORT"
echo "=========================================="

# ------------------------------------------
# 1. Git information
# ------------------------------------------

{
    echo "PROJECT: Yummy"
    echo "BRANCH: $(git branch --show-current)"
    echo "HEAD: $(git rev-parse HEAD)"
    echo
    echo "LATEST COMMITS:"
    git log -12 --oneline --decorate
    echo
    echo "GIT STATUS:"
    git status --short
    echo
    echo "TRACKED CHANGES:"
    git diff HEAD --stat
} > "$STAGE/PROJECT_INFO.txt"

# Simpan perubahan tracked, staged dan unstaged
git diff --binary HEAD > "$STAGE/LOCAL_CHANGES.patch"

# ------------------------------------------
# 2. Theme system
# ------------------------------------------

INCLUDE=(
    "app/src/main/java/com/termux/app/theme"
    "app/src/main/java/com/termux/app/TermuxActivity.java"
    "app/src/main/java/com/termux/app/editor/EditorSettingsController.java"
    "app/src/main/java/com/termux/app/editor/EditorController.java"
    "app/src/main/assets/themes"
    "app/src/main/assets/textmate"
    "app/src/main/res/layout"
    "app/src/main/res/values"
    "app/src/main/res/drawable"
    "app/src/main/res/drawable-nodpi"
    "app/src/main/res/font"
    "app/src/main/AndroidManifest.xml"
    "app/build.gradle"
    "app/build.gradle.kts"
    "build.gradle"
    "build.gradle.kts"
    "settings.gradle"
    "settings.gradle.kts"
    "gradle/libs.versions.toml"
    "gradle.properties"
)

EXISTING=()

for ITEM in "${INCLUDE[@]}"; do
    if [[ -e "$ITEM" ]]; then
        EXISTING+=("$ITEM")
    fi
done

if [[ ${#EXISTING[@]} -eq 0 ]]; then
    echo "ERROR: Tidak ditemukan source yang diperlukan."
    exit 1
fi

# ------------------------------------------
# 3. Copy source
# ------------------------------------------

mkdir -p "$STAGE/project"

for ITEM in "${EXISTING[@]}"; do
    mkdir -p "$STAGE/project/$(dirname "$ITEM")"
    cp -a "$ITEM" "$STAGE/project/$ITEM"
done

# ------------------------------------------
# 4. Include project-level local changes
# ------------------------------------------

mkdir -p "$STAGE/git"

git status --porcelain=v1 -uall > "$STAGE/git/status.txt"
git diff --binary HEAD > "$STAGE/git/local_changes.patch"
git diff --cached --stat > "$STAGE/git/staged_changes.txt"
git diff --stat > "$STAGE/git/unstaged_changes.txt"

# ------------------------------------------
# 5. Create ZIP
# ------------------------------------------

rm -f "$OUTPUT"

(
    cd "$STAGE"
    zip -r -q "$OUTPUT" .
)

# ------------------------------------------
# 6. Verify
# ------------------------------------------

if ! unzip -t "$OUTPUT" >/dev/null 2>&1; then
    echo "ERROR: ZIP gagal diverifikasi."
    exit 1
fi

echo
echo "=========================================="
echo " EXPORT SUCCESS"
echo "=========================================="
echo "Branch : $(git branch --show-current)"
echo "Commit : $(git rev-parse --short HEAD)"
echo "File   : $OUTPUT"
echo "Size   : $(du -h "$OUTPUT" | cut -f1)"
echo
echo "ZIP integrity: OK"

