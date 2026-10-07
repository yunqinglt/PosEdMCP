#!/usr/bin/env bash
# Builds the two things a release is made of: the APK, and the Magisk module as a
# zip the Magisk app can flash.
#
# The zip is not "the module directory, zipped". Magisk wants module.prop at the
# root of the archive and an installer at META-INF/com/google/android/, and it
# says so only by refusing to install anything else - which is how the first
# hand-made one here turned out to be a file nobody could flash. So the archive is
# built to that shape and then checked against it before this script claims it
# worked.
#
# The two artifacts carry their own versions: the APK is named after the app's
# versionName, the zip after the module's own version in module.prop. They move on
# different schedules and pretending otherwise would make one of them a lie.
set -euo pipefail

export MSYS_NO_PATHCONV=1

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Everything below uses paths relative to here. Python on Windows cannot open an
# MSYS path like /e/posedmcp/dist/... - the same trap adb has - and relative paths
# avoid the question entirely.
cd "${REPO_ROOT}"

MODULE_SRC="magisk/posedmcp-guard"
APP_VERSION="$(sed -n 's/.*versionName *= *"\([^"]*\)".*/\1/p' app/build.gradle.kts | head -1)"
MODULE_VERSION="$(sed -n 's/^version=//p' "${MODULE_SRC}/module.prop" | head -1)"

if [[ -z "${APP_VERSION}" || -z "${MODULE_VERSION}" ]]; then
    echo "could not read the versions (app='${APP_VERSION}' module='${MODULE_VERSION}')" >&2
    exit 1
fi

echo "==> building the apk"
"${REPO_ROOT}/tools/gradle.sh" :app:assembleDebug

APK_SRC="app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -f "${APK_SRC}" ]]; then
    echo "no apk at ${APK_SRC}" >&2
    exit 1
fi

mkdir -p dist
APK_OUT="dist/PosEdMCP-${APP_VERSION}.apk"
ZIP_OUT="dist/posedmcp-guard-${MODULE_VERSION}.zip"
rm -f "${APK_OUT}" "${ZIP_OUT}"
cp "${APK_SRC}" "${APK_OUT}"

echo "==> packing the module"
python - "${MODULE_SRC}" "${ZIP_OUT}" <<'PY'
import pathlib
import sys
import zipfile

source = pathlib.Path(sys.argv[1])
target = pathlib.Path(sys.argv[2])

# A fixed timestamp, so two builds of the same tree produce the same bytes and a
# release artifact can be compared against what is in the repository.
STAMP = (1980, 1, 1, 0, 0, 0)

with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(source.rglob("*")):
        if not path.is_file():
            continue
        name = path.relative_to(source).as_posix()
        info = zipfile.ZipInfo(name, date_time=STAMP)
        # Magisk runs the module's scripts through sh, but they are scripts, so
        # the archive stores them executable rather than pretending otherwise.
        mode = 0o755 if (name.endswith(".sh") or name.endswith("update-binary")) else 0o644
        info.external_attr = mode << 16
        archive.writestr(info, path.read_bytes())
PY

echo "==> checking the archive is a module zip and not merely a zip"
python - "${ZIP_OUT}" <<'PY'
import sys
import zipfile

required = {
    "module.prop": "Magisk reads this from the root of the archive",
    "post-fs-data.sh": "the boot counter",
    "service.sh": "the watcher",
    "META-INF/com/google/android/update-binary": "without it nothing can install this",
    "META-INF/com/google/android/updater-script": "Magisk's installer looks for it",
}

with zipfile.ZipFile(sys.argv[1]) as archive:
    names = set(archive.namelist())

missing = [f"{name} ({why})" for name, why in required.items() if name not in names]
if missing:
    for line in missing:
        print(f"  missing: {line}", file=sys.stderr)
    sys.exit(1)

nested = [n for n in names if n.endswith("module.prop") and n != "module.prop"]
if nested:
    print(f"  module.prop is nested under {nested}; Magisk wants it at the root",
          file=sys.stderr)
    sys.exit(1)

print(f"  {len(names)} entries, module.prop at the root, installer present")
PY

echo
echo "release artifacts in dist/:"
ls -l "${APK_OUT}" "${ZIP_OUT}"
echo
echo "app ${APP_VERSION} · module ${MODULE_VERSION}"
