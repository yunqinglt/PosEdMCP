"""Build only; installation and framework scope remain explicit device actions."""
import argparse
from pathlib import Path
import subprocess
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument("--sdk", required=True, type=Path)
parser.add_argument("--java", required=True, type=Path)
parser.add_argument("--out", type=Path, default=Path(".tools/android-runtime-fixture"))
parser.add_argument("--keystore", type=Path, default=Path.home() / ".android/debug.keystore")
args = parser.parse_args()
source = Path(__file__).resolve().parent
out = args.out.resolve()
for folder in ["classes", "dex", "lib/arm64-v8a"]:
    (out / folder).mkdir(parents=True, exist_ok=True)
bt = args.sdk / "build-tools/36.0.0"
android = args.sdk / "platforms/android-36/android.jar"
java = args.java / "bin"


def run(*command):
    subprocess.run([str(item) for item in command], check=True)


run(java / "javac.exe", "--release", "17", "-cp", android, "-d", out / "classes",
    *sorted((source / "src").rglob("*.java")))
run(java / "jar.exe", "cf", out / "fixture.jar", "-C", out / "classes", ".")
run(java / "java.exe", "-cp", bt / "lib/d8.jar", "com.android.tools.r8.D8",
    "--min-api", "31", "--lib", android, "--output", out / "dex", out / "fixture.jar")
run(bt / "aapt2.exe", "link", "-I", android, "--manifest", source / "AndroidManifest.xml",
    "-o", out / "unsigned.apk")
clang = args.sdk / "ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe"
run(clang, "--target=aarch64-linux-android31", "-shared", "-fPIC", source / "fixture.c",
    "-o", out / "lib/arm64-v8a/libruntimefixture.so")
with zipfile.ZipFile(out / "unsigned.apk", "a", compression=zipfile.ZIP_STORED) as apk:
    apk.write(out / "dex/classes.dex", "classes.dex")
    apk.write(out / "lib/arm64-v8a/libruntimefixture.so", "lib/arm64-v8a/libruntimefixture.so")
run(bt / "zipalign.exe", "-f", "-P", "16", "4", out / "unsigned.apk", out / "aligned.apk")
run(java / "java.exe", "-jar", bt / "lib/apksigner.jar", "sign", "--ks", args.keystore,
    "--ks-pass", "pass:android", "--key-pass", "pass:android", "--out", out / "fixture.apk",
    out / "aligned.apk")
print(out / "fixture.apk")
