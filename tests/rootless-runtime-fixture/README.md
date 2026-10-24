# libsu device regression

Build this isolated APK with the resolved official libsu 6.0.0 AAR:

```powershell
python tests/rootless-runtime-fixture/build.py --sdk E:/SDK --java 'C:/Program Files/Java/jdk-22' --libsu <core-6.0.0.aar> --out .tools/rootless-runtime-fixture
```

It compiles the production RootShell directly and includes the AAR's real classes.jar.
Install only for testing, start `dev.posedmcp.rootlessfixture/.FixtureActivity`, and read
`run-as dev.posedmcp.rootlessfixture cat files/result.json`. Remove the APK afterwards.

The app sets only its own process PATH to a private directory containing sh/id but no su,
restoring it in finally. It never changes root grants, device settings or the system PATH.
Tests include root-required refusal without execution, exact EOF/text/binary bytes, bounded
large stdout/stderr, exit status, timeout isolation, concurrency and absent-shell reporting.
Do not add it to framework scope: the test bundles the production dev.posedmcp.root class
and must remain independent of concealment hooks for that namespace.
