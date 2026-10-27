#!/data/data/com.termux/files/usr/bin/bash

set -Eeuo pipefail

# Konfigurasi baru
OLD_PACKAGE="com.termux"
NEW_PACKAGE="com.yummy"
NEW_APP_NAME="Yummy"

ROOT="$(pwd)"
BACKUP_DIR="$ROOT/.backup-package-rename-$(date +%Y%m%d-%H%M%S)"

# Pastikan dijalankan dari root repository
if [[ ! -f "$ROOT/app/build.gradle" ||
      ! -f "$ROOT/termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java" ]]; then
    echo "[ERROR] Jalankan script dari root repository Yummy."
    echo "Contoh: cd ~/Yummy && bash rename-package.sh"
    exit 1
fi

echo "======================================"
echo " Yummy Package Rename"
echo "======================================"
echo "Package lama : $OLD_PACKAGE"
echo "Package baru : $NEW_PACKAGE"
echo "Nama aplikasi: $NEW_APP_NAME"
echo

# Cegah backup ikut masuk ke perubahan
mkdir -p "$BACKUP_DIR"

# Daftar file konfigurasi yang diedit
FILES=(
    "app/build.gradle"
    "app/src/main/res/values/strings.xml"
    "termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java"
)

# Tambahkan file konfigurasi terkait jika ditemukan
for f in \
    app/src/main/res/xml/shortcuts.xml \
    app/src/main/res/xml/shortcut.xml
do
    [[ ! -f "$ROOT/$f" ]] || FILES+=("$f")
done

# Backup seluruh file target
for f in "${FILES[@]}"; do
    mkdir -p "$BACKUP_DIR/$(dirname "$f")"
    cp -p "$ROOT/$f" "$BACKUP_DIR/$f"
done

echo "[1/4] Mengubah konfigurasi package..."

python3 - "${FILES[@]}" "$OLD_PACKAGE" "$NEW_PACKAGE" "$NEW_APP_NAME" <<'PY'
import pathlib
import re
import sys

args = sys.argv[1:]
old_package, new_package, app_name = args[-3:]
files = args[:-3]

for name in files:
    path = pathlib.Path(name)
    text = path.read_text(encoding="utf-8")
    original = text

    if path.name == "build.gradle" and path.parts[:1] == ("app",):
        # Tambahkan applicationId jika belum ada.
        if re.search(r'^\s*applicationId\s+', text, flags=re.M):
            text = re.sub(
                r'(^\s*applicationId\s+)[\'"][^\'"]+[\'"]',
                lambda m: m.group(1) + '"' + new_package + '"',
                text,
                count=1,
                flags=re.M,
            )
        else:
            # Sisipkan ke defaultConfig.
            match = re.search(r'(\bdefaultConfig\s*\{)', text)
            if not match:
                raise SystemExit(
                    f"[ERROR] defaultConfig tidak ditemukan di {path}"
                )
            pos = match.end()
            text = text[:pos] + f'\n        applicationId "{new_package}"' + text[pos:]

        # Placeholder dipakai manifest untuk permission dan authority.
        text = re.sub(
            r'(manifestPlaceholders\.TERMUX_PACKAGE_NAME\s*=\s*)["\'][^"\']*["\']',
            lambda m: m.group(1) + f'"{new_package}"',
            text,
        )

        # Nama aplikasi
        text = re.sub(
            r'(manifestPlaceholders\.TERMUX_APP_NAME\s*=\s*)["\'][^"\']*["\']',
            lambda m: m.group(1) + f'"{app_name}"',
            text,
        )

    elif path.name == "TermuxConstants.java":
        # Ubah nilai konstanta utama, bukan namespace package Java.
        text, count = re.subn(
            r'(public\s+static\s+final\s+String\s+TERMUX_PACKAGE_NAME\s*=\s*)"' +
            re.escape(old_package) + r'"',
            lambda m: m.group(1) + f'"{new_package}"',
            text,
            count=1,
        )
        if count != 1:
            raise SystemExit(
                f"[ERROR] Deklarasi TERMUX_PACKAGE_NAME tidak ditemukan di {path}"
            )

    elif path.name == "strings.xml":
        # Ubah entity package dan nama aplikasi.
        text = re.sub(
            r'<!ENTITY\s+TERMUX_PACKAGE_NAME\s+"[^"]*"\s*>',
            f'<!ENTITY TERMUX_PACKAGE_NAME "{new_package}">',
            text,
        )
        text = re.sub(
            r'<!ENTITY\s+TERMUX_APP_NAME\s+"[^"]*"\s*>',
            f'<!ENTITY TERMUX_APP_NAME "{app_name}">',
            text,
        )

    elif path.name in ("shortcut.xml", "shortcuts.xml"):
        # Hindari perubahan XML lain yang tidak berhubungan.
        text = text.replace(old_package, new_package)

    if text != original:
        path.write_text(text, encoding="utf-8")
        print(f"  [OK] {path}")
    else:
        print(f"  [INFO] Tidak ada perubahan: {path}")
PY

echo
echo "[2/4] Memeriksa nilai konfigurasi..."

grep -nE 'applicationId|manifestPlaceholders\.TERMUX_PACKAGE_NAME|manifestPlaceholders\.TERMUX_APP_NAME' \
    app/build.gradle || true

grep -n 'TERMUX_PACKAGE_NAME =' \
    termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java || true

grep -nE 'TERMUX_PACKAGE_NAME|TERMUX_APP_NAME' \
    app/src/main/res/values/strings.xml || true

echo
echo "[3/4] Memeriksa sisa referensi package lama di file konfigurasi..."

grep -nH 'com\.termux' \
    app/build.gradle \
    app/src/main/res/values/strings.xml \
    termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java \
    || true

echo
echo "[4/4] Selesai."
echo "Backup tersimpan di:"
echo "  $BACKUP_DIR"
echo
echo "[PERINGATAN] Perubahan package saja belum menjamin bootstrap kompatibel."
echo "Periksa bootstrap dan path biner sebelum menggunakan terminal Yummy."
echo
echo "Periksa perubahan dengan:"
echo "  git diff -- app/build.gradle app/src/main/res/values/strings.xml termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java"
