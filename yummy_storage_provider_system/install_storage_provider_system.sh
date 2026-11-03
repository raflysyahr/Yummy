#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
SOURCE_ROOT="${SCRIPT_DIR}/app/src/main/java/com/termux/app/filemanager/storage"
TARGET_ROOT="${PROJECT_ROOT}/app/src/main/java/com/termux/app/filemanager/storage"

fail() {
    printf 'ERROR: %s\n' "$1" >&2
    exit 1
}

[[ -f "${PROJECT_ROOT}/app/build.gradle" ]] || fail "Run this installer from a folder directly inside the Yummy repository (expected app/build.gradle)."
[[ -f "${PROJECT_ROOT}/app/src/main/java/com/termux/app/editor/EditorController.java" ]] || fail "This does not look like the feature/editor branch of Yummy. EditorController.java was not found."
grep -q 'TermuxDocumentsProvider' "${PROJECT_ROOT}/app/src/main/AndroidManifest.xml" || fail "Expected the existing TermuxDocumentsProvider declaration in AndroidManifest.xml."
[[ -d "${SOURCE_ROOT}" ]] || fail "Source package is missing from the extracted bundle."

mkdir -p "${TARGET_ROOT}"

while IFS= read -r -d '' source_file; do
    relative_path="${source_file#${SOURCE_ROOT}/}"
    target_file="${TARGET_ROOT}/${relative_path}"
    mkdir -p "$(dirname "${target_file}")"

    if [[ -e "${target_file}" ]]; then
        if cmp -s "${source_file}" "${target_file}"; then
            printf 'SKIP (already installed): %s\n' "${relative_path}"
            continue
        fi
        fail "Conflicting file already exists; it was not changed: ${target_file}"
    fi

    cp "${source_file}" "${target_file}"
    printf 'ADD: %s\n' "${target_file#${PROJECT_ROOT}/}"
done < <(find "${SOURCE_ROOT}" -type f -name '*.java' -print0)

printf '\nStorage Provider System files installed.\n'
printf 'No existing source, manifest, or Gradle file was modified.\n'
printf 'Next: run ./gradlew :app:compileDebugJavaWithJavac (or assembleDebug).\n'
