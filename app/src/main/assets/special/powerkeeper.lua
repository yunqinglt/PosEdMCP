-- Counter-powerkeeper: stop HyperOS force-stopping this app, and nothing else.
--
-- Shipped inside the APK and released by a switch in Special settings; it is not
-- an ordinary hook and does not appear on the Hooks page. See SpecialHooks.
--
-- The shape of this one is different from the ColorOS countermeasure, because
-- MIUI is built differently. There is no single MIUI handler carrying a reason:
-- the cleaners live in several classes (ProcessCleanerBase, GameProcessKiller,
-- ProcessKiller, MemoryStandardProcessControl) and com.miui.powerkeeper is only
-- a client of them - it maps PowerKeeper.apk and miui-framework.jar and nothing
-- else, so it never does the killing itself. What they all have in common is
-- where they end up: this method, in system_server.
--
-- So the hook sits on the sink rather than on any one caller, and the reason
-- string is what keeps it narrow. These are MIUI's own names for "a cleaner did
-- this", so a force-stop the user asked for - Settings' Force stop, an uninstall,
-- the shell - carries a different one and goes through untouched. That matters
-- more here than on ColorOS, where the hook was on an OPPO-only method: this one
-- is ordinary AMS, shared by every caller in the system.

local BRIDGE = 'dev.posedmcp'

-- Every name MIUI's cleaners pass, from ProcessCleanerBase.getKillReason.
local CLEANER_REASONS = {
  'OneKeyClean', 'ForceClean', 'LockScreenClean', 'GameClean',
  'OptimizationClean', 'GarbageClean', 'SwipeUpClean', 'UserDefined',
  'AutoPowerKill', 'AutoThermalKill', 'AutoIdleKill', 'AutoSleepClean',
  'AutoSystemAbnormalClean', 'AutoThermalKillAll1', 'AutoThermalKillAll2',
  'ScreenOffCPUCheckKill',
}

local function isBridge(name)
  if name == nil then return false end
  local s = tostring(name)
  return s == BRIDGE or string.sub(s, 1, #BRIDGE + 1) == BRIDGE .. ':'
end

-- Prefix, not equality: the name is what the cleaner chose, and a suffix added
-- to it later should not silently take the countermeasure out of the way.
local function byACleaner(reason)
  if reason == nil then return false end
  local s = tostring(reason)
  for i = 1, #CLEANER_REASONS do
    if string.sub(s, 1, #CLEANER_REASONS[i]) == CLEANER_REASONS[i] then
      return true
    end
  end
  return false
end

app.hook{
  class = 'com.android.server.am.ActivityManagerService',
  method = 'forceStopPackage',
  params = 'java.lang.String,int,int,java.lang.String',
  effect = 'HyperOS force-stopping the bridge has no effect (other apps unchanged)',
  before = function(ctx)
    local pkg = tostring(ctx.args[1])
    local reason = tostring(ctx.args[4])
    if isBridge(pkg) and byACleaner(reason) then
      app.log('counter-powerkeeper: refused force-stop of ' .. pkg
              .. ' reason=' .. tostring(reason))
      -- void method: setting a result is what makes the framework skip it.
      ctx.set_result()
    end
  end
}
