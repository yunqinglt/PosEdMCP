# Hook regression

Run `powershell -File tests/run-hooks.ps1` after the app dependencies have been
resolved. The harness compiles production HookRegistry/Framework against a fake
HookApi and runs the actual shipped Lua scripts with LuaJ. It never connects to a
phone or changes system_server. It covers owner coexistence, reapply, nested
records, independent pause (including multiple methods under one special owner),
ordinary clear, exact package/subprocess matching, HyperOS cleaner versus manual
force-stop reasons (84 cases across both scripts), and ColorOS Hans importance
exemption restricted to Deploy (6 cases).

The host JVM also needs `org.json:json:20240303` (Android provides it on device).
Download https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar
into `.tools/test-libs/`. The runner verifies SHA-256
`3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed`.
Use your local proxy if required; no jar is committed. Override `JavaDirectory`
or `GradleCache` when the local installation differs.

Run `powershell -File tests/run-runtime.ps1` after building the app for the runtime
policy regressions. It executes production callbacks against fake hook bindings,
including external callers, real BufferedReader streams, class lookup outcomes,
genuine JNI modifier preservation and libxposed exception replacement. It also
exercises the production bootstrap snapshot reader/writer with platform fixtures,
UID filtering, config updates without restarting the reader, corruption handling
and token redaction. A host GCC runs the actual C DNS matcher. Windows WinLibs in
a Unicode home path uses an ignored local junction and explicit linker prefixes.
Platform fixtures live under `tests/stubs` and are never part of the app APK.

Package visibility checks execute production PM callbacks and stdout filtering with mixed UID arrays,
immutable lists, Binder slices, original permission exceptions and policy toggles. GCC also executes
the native package-path matcher, including exact package boundaries and relative path normalization.

Dex/file regressions preserve real Field objects and original typed arrays/lists, filter only
the returned dex path view, and check exact artifact metadata/open/list callbacks and toggles.
The runtime suite now passes 148 assertions, including the native framework-path rules.

The hook suite also runs the bridge Athena preset, checking its Hans importance
exemption for the bridge and subprocesses, and rejecting similar or unrelated packages.

## Android runtime checks

See [android-runtime-fixture](android-runtime-fixture/README.md) for the disposable on-device
startup, concealment and libc DNS control application. It is separate from the host regressions.
