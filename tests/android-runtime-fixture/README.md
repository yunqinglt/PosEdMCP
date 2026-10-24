# Android runtime fixture

This fixture intentionally uses `dev.deploy.fixture`, the existing disposable Deploy test app scope.
Back up its original APK before replacing it, and restore it after testing. The build command does
not install, change scopes, change policies, or reboot the device.

```powershell
python tests/android-runtime-fixture/build.py --sdk E:/SDK --java 'C:/Program Files/Java/jdk-22'
adb -s SERIAL install -r -t .tools/android-runtime-fixture/fixture.apk
adb -s SERIAL shell am start -W -n dev.deploy.fixture/.FixtureActivity
adb -s SERIAL shell run-as dev.deploy.fixture cat files/result.json
```

Enable this test package in the module's framework scope. Restart only the fixture between cases.
Wait for `result.json` to contain the current process PID before reading assertions.

- Register a temporary kept hook on `dev.deploy.fixture.Sentinel#answer` returning `early`, with
  observation disabled. After a cold launch, both `provider` and `application` must be `early`.
  This tests restoration before provider/Application startup, rather than a later socket connection.
- With concealment enabled, `hiddenStack` must be `android.os.Handler`, `ownStack` must retain the
  fixture's name, and `classProbe` must be `hidden`. The marker class is included in the fixture APK:
  disabling the policy must change the probe to `visible`.
- `javaNativeBit` and the hooked `sentinelNativeBit` must be false; `jniNativeBit` must stay true.
- When the module native library is loaded, `nativeModuleMaps` must be positive while
  `javaModuleMaps` is zero. `ordinaryReader` must stay `libposednative.so|keep|null`.
- With DNS rules `example.com` and `*.ads.example.com`, `dnsExact` and `dnsWildcard` must return
  libc's `EAI_NONAME`. `scope_status` must show two blocked lookups. Numeric and localhost lookups
  must still succeed. A successful Internet lookup without filtering depends on network availability.
- Remove the temporary ordinary hook: `scope_status.hooks` becomes zero while internal hooks remain
  installed. Clear test domains and restore the desired policy switches before restoring the APK.

The fixture intentionally reads raw maps through JNI as a control; the Java policy does not filter
native maps reads. It does not load module code or implement a module bridge itself.
