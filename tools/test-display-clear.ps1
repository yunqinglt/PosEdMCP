. "$PSScriptRoot/DisplayDeviceSession.ps1"
Open-DisplaySession
try {
    $runId = [Guid]::NewGuid().ToString('N')
    foreach ($pkg in @('com.x1y9.probe','com.microsoft.emmx')) {
        $target = Invoke-DisplayTool display_get_target @{package=$pkg}
        if (!$target.ok) { throw 'Target query failed' }
        if ($null -eq $target.data.profileId) { continue }
        $result = Invoke-DisplayTool display_clear_target @{package=$pkg;expectedTargetRevision=$target.data.revision;requestId=($runId + '-clear-' + $pkg);reason='结束临时实机测试并撤回配置；应用已缓存的值需要刷新或重启。'}
        $result | ConvertTo-Json -Depth 12
        if (!$result.ok) { throw "Clear failed: $pkg" }
    }
} finally { Close-DisplaySession }
