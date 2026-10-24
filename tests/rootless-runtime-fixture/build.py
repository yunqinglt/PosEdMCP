"""Build an isolated runtime regression APK using the resolved real libsu artifact."""
import argparse, pathlib, subprocess, zipfile
p = argparse.ArgumentParser()
p.add_argument('--sdk', required=True, type=pathlib.Path)
p.add_argument('--java', required=True, type=pathlib.Path)
p.add_argument('--libsu', required=True, type=pathlib.Path)
p.add_argument('--out', required=True, type=pathlib.Path)
a = p.parse_args()
source = pathlib.Path(__file__).resolve().parent
repo = source.parent.parent
out = a.out.resolve()
for name in ['classes', 'dex']:
    (out / name).mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(a.libsu) as aar:
    (out / 'libsu.jar').write_bytes(aar.read('classes.jar'))
bt = a.sdk / 'build-tools/36.0.0'
android = a.sdk / 'platforms/android-36/android.jar'
java = a.java / 'bin'
def run(*cmd): subprocess.run([str(c) for c in cmd], check=True)
run(java/'javac.exe', '--release', '17', '-encoding', 'UTF-8', '-cp', str(android)+';'+str(out/'libsu.jar'),
    '-d', out/'classes', repo/'app/src/main/java/dev/posedmcp/root/RootShell.java', *source.glob('src/**/*.java'))
run(java/'jar.exe', 'cf', out/'fixture.jar', '-C', out/'classes', '.')
run(java/'java.exe', '-cp', bt/'lib/d8.jar', 'com.android.tools.r8.D8', '--min-api', '31', '--lib', android,
    '--output', out/'dex', out/'fixture.jar', out/'libsu.jar')
run(bt/'aapt2.exe', 'link', '-I', android, '--manifest', source/'AndroidManifest.xml', '-o', out/'unsigned.apk')
with zipfile.ZipFile(out/'unsigned.apk', 'a', compression=zipfile.ZIP_STORED) as apk:
    apk.write(out/'dex/classes.dex', 'classes.dex')
run(bt/'zipalign.exe', '-f', '4', out/'unsigned.apk', out/'aligned.apk')
run(java/'java.exe', '-jar', bt/'lib/apksigner.jar', 'sign', '--ks', pathlib.Path.home()/'.android/debug.keystore',
    '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', out/'fixture.apk', out/'aligned.apk')
print(out/'fixture.apk')
