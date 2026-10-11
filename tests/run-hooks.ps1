param(
    [string]$JavaDirectory = 'C:/Program Files/Java/jdk-22',
    [string]$GradleCache = "$PSScriptRoot/../.gradle-home/caches/modules-2/files-2.1"
)
$ErrorActionPreference = 'Stop'
Push-Location "$PSScriptRoot/.."
try {
    $jsonJar = '.tools/test-libs/json-20240303.jar'
    if (!(Test-Path -LiteralPath $jsonJar)) {
        throw 'Download org.json:json:20240303 from Maven Central to .tools/test-libs/json-20240303.jar first; see tests/README.md.'
    }
    if ((Get-FileHash -LiteralPath $jsonJar).Hash -ne '3CF6CD6892E32E2B4C1C39E0F52F5248A2F5B37646FDFBB79A66B46B618414ED') { throw 'Unexpected JSON jar checksum' }
    $luaJar = Get-ChildItem -LiteralPath "$GradleCache/org.luaj/luaj-jse/3.0.1" -Recurse -Filter '*.jar' | Select-Object -First 1 -ExpandProperty FullName
    $xposedJar = Get-ChildItem -LiteralPath "$GradleCache/de.robv.android.xposed/api/82" -Recurse -Filter '*.jar' | Select-Object -First 1 -ExpandProperty FullName
    $classpath = "$jsonJar;$luaJar;$xposedJar"
    New-Item -ItemType Directory -Force .tools/test-classes | Out-Null
    & "$JavaDirectory/bin/javac.exe" -cp $classpath -d .tools/test-classes tests/HooksRegression.java app/src/main/java/dev/posedmcp/plugin/HookApi.java app/src/main/java/dev/posedmcp/xposed/Framework.java app/src/main/java/dev/posedmcp/xposed/XposedHookApi.java app/src/main/java/dev/posedmcp/xposed/HookRegistry.java
    if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
    & "$JavaDirectory/bin/java.exe" -cp ".tools/test-classes;$classpath" dev.posedmcp.xposed.HooksRegression
    if ($LASTEXITCODE -ne 0) { throw 'Regression failed' }
} finally { Pop-Location }
