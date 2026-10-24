# Dot-source only. Tokens stay in memory; callers must Close-DisplaySession in finally.
$ErrorActionPreference = 'Stop'
$script:displayAdb = 'E:/SDK/platform-tools/adb.exe'
$script:displaySerial = '3972b041'
$script:displayRpcSequence = 0
function Open-DisplaySession {
    [xml]$prefs = (& $script:displayAdb -s $script:displaySerial shell run-as dev.posedmcp cat shared_prefs/posedmcp.xml) -join "`n"
    $token = ($prefs.map.string | Where-Object name -eq 'mcp_token').InnerText
    if (!$token) { throw 'MCP credential unavailable' }
    $script:displayHeaders = @{Authorization=('Bearer ' + $token)}
    $script:displayPort = (& $script:displayAdb -s $script:displaySerial forward tcp:0 tcp:8765).Trim()
}
function Invoke-DisplayRpc([string]$Method, [hashtable]$Parameters) {
    $script:displayRpcSequence++
    $json = @{jsonrpc='2.0'; id=$script:displayRpcSequence; method=$Method; params=$Parameters} | ConvertTo-Json -Depth 30 -Compress
    # The device confirmation window itself can last 120 s, then peer execution may take 30 s.
    $reply = Invoke-RestMethod -Uri "http://127.0.0.1:$script:displayPort/mcp" -Method Post -Headers $script:displayHeaders -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($json)) -TimeoutSec 180
    if ($reply.error) { throw "MCP RPC failed: $($reply.error.code)" }
    return $reply.result
}
function Invoke-DisplayTool([string]$Name, [hashtable]$Arguments = @{}) {
    $result = Invoke-DisplayRpc 'tools/call' @{name=$Name; arguments=$Arguments}
    $payload = if ($result.structuredContent) { $result.structuredContent } else {
        $rawText = ($result.content | Where-Object type -eq 'text' | Select-Object -First 1).text
        try { $rawText | ConvertFrom-Json } catch { @{ok=$false;error=@{code='MCP_TOOL_ERROR';message=$rawText}} }
    }
    return $payload
}
function Close-DisplaySession {
    if ($script:displayPort -match '^\d+$') { & $script:displayAdb -s $script:displaySerial forward --remove "tcp:$script:displayPort" | Out-Null }
    $script:displayHeaders = $null
}
