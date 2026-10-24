param([switch]$IncludeEdgeLocation)
. "$PSScriptRoot/DisplayDeviceSession.ps1"
Open-DisplaySession
try {
    $runId = [Guid]::NewGuid().ToString('N')
    function Save-TestProfile([hashtable]$Profile, [string]$Suffix) {
        $previous = Invoke-DisplayTool display_get_profile @{profileId=$Profile.id}
        if (!$previous.ok -and $previous.error.code -ne 'NOT_FOUND') { throw 'Profile query failed' }
        $revision = if ($previous.ok) { $previous.data.revision } else { 0 }
        $result = Invoke-DisplayTool display_put_profile @{profile=$Profile; expectedRevision=$revision; requestId=($runId + '-save-' + $Suffix); reason='保存临时测试配置，仅保存；更新示例不改变已绑定的快照。测试后请运行撤回脚本。'}
        if (!$result.ok) { throw "Profile save failed: $($result.error.code)" }
        return $result.data
    }
    function Apply-TestProfile([string]$Package, $Saved, [string]$Suffix) {
        $target = Invoke-DisplayTool display_get_target @{package=$Package}
        if (!$target.ok) { throw 'Target query failed' }
        $result = Invoke-DisplayTool display_apply_profile @{package=$Package; profileId=$Saved.profile.id; profileRevision=$Saved.revision; expectedTargetRevision=$target.data.revision; requestId=($runId + '-apply-' + $Suffix); reason='在已勾选作用域中应用临时测试规则；请验证 ACK 和取值，测试后撤回。'}
        $result | ConvertTo-Json -Depth 12
        if (!$result.ok) { throw "Apply failed: $Package" }
    }
    # Mixed rules previously crashed Edge at cold startup. Never apply them there.
    $profile = Get-Content -Raw D:/Display/examples/tokyo-device-test.json | ConvertFrom-Json -AsHashtable
    $saved = Save-TestProfile $profile 'probe'
    Apply-TestProfile 'com.x1y9.probe' $saved 'probe'
    if ($IncludeEdgeLocation) {
        $location = @{schemaVersion=1;id='tokyo-location-only';rules=@(@{key='location.position';value=@{latitude=35.681236;longitude=139.767125;accuracyMeters=12}})}
        $savedLocation = Save-TestProfile $location 'edge'
        Apply-TestProfile 'com.microsoft.emmx' $savedLocation 'edge'
    }
} finally { Close-DisplaySession }
