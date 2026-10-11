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
