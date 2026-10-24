param([string]$JavaDirectory = 'C:/Program Files/Java/jdk-22')
$ErrorActionPreference = 'Stop'
Push-Location "$PSScriptRoot/.."
try {
    $jsonJar = '.tools/test-libs/json-20240303.jar'
    if ((Get-FileHash -LiteralPath $jsonJar).Hash -ne '3CF6CD6892E32E2B4C1C39E0F52F5248A2F5B37646FDFBB79A66B46B618414ED') { throw 'Unexpected JSON jar checksum' }
    $apiJar = Get-ChildItem .gradle-home/caches -Recurse -Filter 'api-102.0.0-api.jar' | Select-Object -First 1 -ExpandProperty FullName
    $xposedJar = Get-ChildItem .gradle-home/caches/modules-2/files-2.1/de.robv.android.xposed/api/82 -Recurse -Filter '*.jar' | Select-Object -First 1 -ExpandProperty FullName
    if (!$apiJar) { throw 'Build the app first to resolve the libxposed API' }
    $classpath = "$jsonJar;$apiJar;$xposedJar"
    $sources = @('tests/RuntimePoliciesRegression.java', 'tests/BootstrapRegression.java', 'tests/PackageVisibilityRegression.java',
        'tests/stubs/android/os/Process.java', 'tests/stubs/android/os/Bundle.java',
        'tests/stubs/android/content/Context.java', 'tests/stubs/android/content/pm/ApplicationInfo.java',
        'tests/stubs/android/content/pm/PackageManager.java', 'tests/stubs/android/util/AtomicFile.java',
        'tests/stubs/android/content/pm/ParceledListSlice.java',
        'tests/stubs/dev/posedmcp/state/Prefs.java', 'tests/stubs/dev/posedmcp/state/RuntimePolicy.java',
        'tests/stubs/dev/posedmcp/state/HookStore.java',
        'tests/stubs/dev/posedmcp/ipc/FrameworkPreferencesProvider.java',
        'tests/stubs/dev/posedmcp/Logx.java', 'app/src/main/java/dev/posedmcp/plugin/HookApi.java',
        'app/src/main/java/dev/posedmcp/state/DomainRules.java',
        'app/src/main/java/dev/posedmcp/state/SavedHook.java',
        'app/src/main/java/dev/posedmcp/state/HookMirror.java',
        'app/src/main/java/dev/posedmcp/ipc/BridgeBootstrap.java',
        'app/src/main/java/dev/posedmcp/xposed/HookRegistry.java',
        'app/src/main/java/dev/posedmcp/diagnostics/DiagnosticRedactor.java',
        'app/src/main/java/dev/posedmcp/xposed/ScopeConcealment.java',
        'app/src/main/java/dev/posedmcp/xposed/ConcealmentPackages.java',
        'app/src/main/java/dev/posedmcp/xposed/PackageConcealment.java',
        'app/src/main/java/dev/posedmcp/xposed/PackageListFilter.java',
        'app/src/main/java/dev/posedmcp/xposed/HookVisibility.java',
        'app/src/main/java/dev/posedmcp/xposed/Framework.java',
        'app/src/main/java/dev/posedmcp/xposed/XposedHookApi.java',
        'app/src/main/java/dev/posedmcp/xposed/LibXposedHookApi.java')
    New-Item -ItemType Directory -Force .tools/runtime-test-classes | Out-Null
    & "$JavaDirectory/bin/javac.exe" -encoding UTF-8 -cp $classpath -d .tools/runtime-test-classes @sources
    if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
    & "$JavaDirectory/bin/java.exe" -cp ".tools/runtime-test-classes;$classpath" fixture.RuntimePoliciesRegression
    if ($LASTEXITCODE -ne 0) { throw 'Runtime policy regression failed' }
    & "$JavaDirectory/bin/java.exe" -cp ".tools/runtime-test-classes;$classpath" fixture.PackageVisibilityRegression
    if ($LASTEXITCODE -ne 0) { throw 'Package visibility regression failed' }
    & "$JavaDirectory/bin/java.exe" -cp ".tools/runtime-test-classes;$classpath" fixture.BootstrapRegression
    if ($LASTEXITCODE -ne 0) { throw 'Bootstrap regression failed' }
    $compiler = (Get-Command gcc -ErrorAction Stop).Source
    $compilerArgs = @()
    # WinLibs cannot pass Unicode install paths to its linker. A local junction
    # plus explicit search prefixes works without copying or changing the compiler.
    if ($compiler -match '[^\x00-\x7F]') {
        $toolchain = Split-Path (Split-Path $compiler)
        $alias = Join-Path (Get-Location) '.tools/runtime-gcc'
        if (!(Test-Path -LiteralPath $alias)) {
            New-Item -ItemType Junction -Path $alias -Target $toolchain | Out-Null
        }
        $versionDir = Get-ChildItem "$alias/lib/gcc/x86_64-w64-mingw32" -Directory |
            Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
        $compiler = "$alias/bin/gcc.exe"
        $compilerArgs = @('-B', "$versionDir/", '-B', "$alias/x86_64-w64-mingw32/lib/",
            '-L', "$alias/x86_64-w64-mingw32/lib")
    }
    & $compiler @compilerArgs -std=c11 -Wall -Wextra -Werror tests/dns_rules_test.c -o .tools/runtime-test-classes/dns_rules_test.exe
    if ($LASTEXITCODE -ne 0) { throw 'Native matcher compilation failed' }
    & ./.tools/runtime-test-classes/dns_rules_test.exe
    if ($LASTEXITCODE -ne 0) { throw 'Native matcher regression failed' }
    & $compiler @compilerArgs -std=c11 -Wall -Wextra -Werror tests/package_paths_test.c -o .tools/runtime-test-classes/package_paths_test.exe
    if ($LASTEXITCODE -ne 0) { throw 'Native package path compilation failed' }
    & ./.tools/runtime-test-classes/package_paths_test.exe
    if ($LASTEXITCODE -ne 0) { throw 'Native package path regression failed' }
} finally { Pop-Location }
