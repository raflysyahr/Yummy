#!/data/data/com.termux/files/usr/bin/bash

# ============================================================
# YUMMY - REPLACE FILE MANAGER ICONS
# ============================================================

set -e

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
SOURCE_DIR="$PROJECT_DIR/icons"
DRAWABLE_DIR="$PROJECT_DIR/app/src/main/res/drawable"

# ============================================================
# VALIDASI
# ============================================================

if [ ! -d "$SOURCE_DIR" ]; then
    echo "[ERROR] Folder icons tidak ditemukan: $SOURCE_DIR"
    exit 1
fi

if [ ! -d "$DRAWABLE_DIR" ]; then
    echo "[ERROR] Folder drawable tidak ditemukan."
    exit 1
fi

# ============================================================
# PEMETAAN IKON
# Format: file_sumber.xml -> nama_drawable_tujuan.xml
# ============================================================

declare -A ICONS=(
    ["add.xml"]="ic_fm_storage_add.xml"
    ["arrow-right.xml"]="ic_fm_chevron_right.xml"
    ["home.xml"]="ic_fm_storage_local.xml"
    ["privat.xml"]="ic_fm_storage_private.xml"
    ["search.xml"]="ic_fm_search.xml"
    ["storage.xml"]="ic_fm_storage_all.xml"
    ["termux.xml"]="ic_fm_storage_external.xml"
)

# ============================================================
# PROSES PENGGANTIAN
# ============================================================

echo "========================================"
echo " YUMMY ICON REPLACEMENT"
echo "========================================"

for SOURCE in "${!ICONS[@]}"; do

    TARGET="${ICONS[$SOURCE]}"

    SOURCE_FILE="$SOURCE_DIR/$SOURCE"
    TARGET_FILE="$DRAWABLE_DIR/$TARGET"

    if [ ! -f "$SOURCE_FILE" ]; then
        echo "[SKIP] File tidak ditemukan: $SOURCE"
        continue
    fi

    cp -f "$SOURCE_FILE" "$TARGET_FILE"

    echo "[OK] $SOURCE -> $TARGET"

done

echo "========================================"
echo " SELESAI"
echo "========================================"
