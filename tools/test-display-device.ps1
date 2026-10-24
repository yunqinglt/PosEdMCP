param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$Adb = 'E:/SDK/platform-tools/adb.exe',
    [string]$OutputPath
)
$ErrorActionPreference = 'Stop'
$forwardPort = $null
$testHeaders = $null
$checks = [System.Collections.Generic.List[object]]::new()
$script:displayTestSequence = 0
function Rpc([string]$Method, [hashtable]$Parameters) {
    $script:displayTestSequence++
    $body = @{jsonrpc='2.0'; id=$script:displayTestSequence; method=$Method; params=$Parameters} | ConvertTo-Json -Depth 20 -Compress
    $reply = Invoke-RestMethod -Uri "http://127.0.0.1:$forwardPort/mcp" -Method Post -Headers $testHeaders -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 20
    if ($reply.error) { throw "MCP RPC error: $($reply.error.code)" }
    return $reply.result
}
function Tool([string]$Name, [hashtable]$Arguments = @{}) {
    $result = Rpc 'tools/call' @{name=$Name; arguments=$Arguments}
    $payload = if ($result.structuredContent) { $result.structuredContent } else { ($result.content | Where-Object type -eq 'text' | Select-Object -First 1).text | ConvertFrom-Json }
    return @{isError=[bool]$result.isError; payload=$payload}
}
function Check([string]$Name, [bool]$Passed) {
    $checks.Add(@{name=$Name; passed=$Passed})
    if (!$Passed) { throw "Device check failed: $Name" }
}
function CoreMissing([string]$Name, [hashtable]$Arguments) {
    $reply = Tool $Name $Arguments
    Check $Name ($reply.isError -and $reply.payload.error.code -eq 'CORE_UNAVAILABLE')
}
try {
    # Credentials remain in memory; never include prefs, auth headers or token in reports.
    [xml]$testPrefs = (& $Adb -s $Serial shell run-as dev.posedmcp cat shared_prefs/posedmcp.xml) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw 'Unable to read the debug app MCP credential' }
    $testToken = ($testPrefs.map.string | Where-Object name -eq 'mcp_token').InnerText
    if (!$testToken) { throw 'Launch PosEdMCP to initialize its MCP credential first' }
    $testHeaders = @{Authorization=('Bearer '+$testToken)}
    $testPortNode = $testPrefs.map.int | Where-Object name -eq 'mcp_port'
    $remotePort = if ($testPortNode) { [int]$testPortNode.value } else { 8765 }
    $forwardPort = (& $Adb -s $Serial forward tcp:0 "tcp:$remotePort").Trim()
    if ($LASTEXITCODE -ne 0 -or $forwardPort -notmatch '^\d+$') { throw 'Unable to allocate adb forwarding' }
    $status = Tool 'display_status'
    if ($status.isError) { throw "Display status failed: $($status.payload.error.code): $($status.payload.error.message)" }
    Check 'display_status AIDL reply' (!$status.isError -and $status.payload.ok -and $status.payload.apiVersion -eq 1)
    # These tests must never mutate a live Core or prompt for an actual write.
    Check 'frontend-only backend absent' ($status.payload.data.coreAvailable -eq $false)
    $tools = Rpc 'tools/list' @{}
    $displayTools = @($tools.tools | Where-Object name -like 'display_*')
    Check 'ten registered Display tools' ($displayTools.Count -eq 10)
    $module = Tool 'module_status'
    Check 'system bridge online' ($module.payload.systemBridgeConnected -eq $true)
    $peers = @($module.payload.bridgePeers)
    Check 'parameter app process online' (@($peers | Where-Object { $_ -like 'app:com.x1y9.probe:*' }).Count -gt 0)
    Check 'Edge process online' (@($peers | Where-Object { $_ -like 'app:com.microsoft.emmx:*' }).Count -gt 0)
    $caps = Tool 'display_capabilities'
    Check 'twenty contract rules' (!$caps.isError -and @($caps.payload.data.rules).Count -eq 20)
    Check 'no runtime rule falsely available' (@($caps.payload.data.rules | Where-Object runtimeSupported -ne $false).Count -eq 0)
    $profile = @{schemaVersion=1; id='device-validation'; rules=@(@{key='state.batteryLevel'; value=80}, @{key='locale.languageTags'; value=@('zh-CN','en-US')})}
    $validated = Tool 'display_validate_profile' @{profile=$profile}
    Check 'profile crosses real Binder and validates' (!$validated.isError -and $validated.payload.data.valid -and !$validated.payload.data.persisted -and !$validated.payload.data.applied -and !$validated.payload.data.runtimeVerified)
    CoreMissing 'display_get_profile' @{profileId='device-validation'}
    CoreMissing 'display_get_target' @{package='com.x1y9.probe'}
    CoreMissing 'display_runtime_state' @{package='com.microsoft.emmx'}
    CoreMissing 'display_put_profile' @{profile=$profile; expectedRevision=0; requestId='device-put'; reason='Verify unavailable Core is reported without writing'}
    CoreMissing 'display_delete_profile' @{profileId='device-validation'; expectedRevision=1; requestId='device-delete'; reason='Verify unavailable Core is reported without deleting'}
    CoreMissing 'display_apply_profile' @{package='com.x1y9.probe'; profileId='device-validation'; profileRevision=1; expectedTargetRevision=0; requestId='device-apply'; reason='Verify unavailable Core is reported without applying'}
    CoreMissing 'display_clear_target' @{package='com.microsoft.emmx'; expectedTargetRevision=0; requestId='device-clear'; reason='Verify unavailable Core is reported without clearing'}
    $deploy = Tool 'deploy_status'
    Check 'Deploy companion AIDL reachable' (!$deploy.isError -and !$deploy.payload.error -and $deploy.payload.apiVersion -eq 2)
    $report = [ordered]@{
        platform='Xiaomi 15 / Android 15 / API 35'; checks=$checks; passed=$checks.Count
        displayCoreAvailable=$false; displayRuleCount=20; displayToolCount=10
        systemBridgeConnected=$true
        onlineTargets=@('com.x1y9.probe','com.microsoft.emmx')
        writesPerformed=$false; frameworkSpoofingTested=$false
    }
    if ($OutputPath) { $report | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $OutputPath -Encoding utf8 }
    $report | ConvertTo-Json -Depth 12
} finally {
    if ($forwardPort -match '^\d+$') { & $Adb -s $Serial forward --remove "tcp:$forwardPort" | Out-Null }
    $testToken=$null; $testHeaders=$null; $testPrefs=$null
}
