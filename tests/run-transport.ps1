param([string]$JavaDirectory = 'C:/Program Files/Java/jdk-22')
$ErrorActionPreference = 'Stop'
Push-Location "$PSScriptRoot/.."
try {
    New-Item -ItemType Directory -Force .tools/transport-test-classes | Out-Null
    & "$JavaDirectory/bin/javac.exe" -encoding UTF-8 -d .tools/transport-test-classes tests/TransportRegression.java tests/stubs/dev/posedmcp/Logx.java app/src/main/java/dev/posedmcp/mcp/EndpointAddresses.java app/src/main/java/dev/posedmcp/mcp/HttpTransport.java
    if ($LASTEXITCODE -ne 0) { throw 'Transport compilation failed' }
    & "$JavaDirectory/bin/java.exe" -cp .tools/transport-test-classes fixture.TransportRegression
    if ($LASTEXITCODE -ne 0) { throw 'Transport regression failed' }
} finally { Pop-Location }
