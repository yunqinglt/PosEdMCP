-- Counter-Athena: stop ColorOS force-stopping this app, and nothing else.
--
-- Shipped inside the APK and released by a switch in Special settings; it is not
-- an ordinary hook and does not appear on the Hooks page. See SpecialHooks.
--
-- Where this has to go, and why, is the whole of the work here. The first
-- attempt hooked Athena's own force-stop funnel inside com.oplus.athena. It
-- installed cleanly and intercepted fine when called by hand - and recorded
-- nothing while the phone really did force-stop apps, because the o-stop comes
-- from a copy of Athena's code loaded into system_server, not from its app
-- process. /proc/<system_server>/maps maps Athena.apk; that process is where it
-- runs.
--
-- So the hook sits on the transact handler in system_server that the o-stop is
-- delivered to. Only Athena's own path passes through here: Settings' "force
-- stop" and `am force-stop` go to ActivityManagerService directly, so the user
-- keeps their own way to stop this app.
--
-- The package is matched exactly, plus its `:` sub-processes. Anything else
-- falls straight through and Athena goes on working as it always did.

local BRIDGE = 'dev.posedmcp'

local function isBridge(name)
  if name == nil then return false end
  local s = tostring(name)
  return s == BRIDGE or string.sub(s, 1, #BRIDGE + 1) == BRIDGE .. ':'
end

app.hook{
  class = 'com.android.server.am.OplusAthenaAmManager',
  method = 'forceStopPackage',
  params = 'java.lang.String,int,int,int,java.lang.String,java.lang.String',
  effect = 'Athena force-stopping the bridge has no effect (other apps unchanged)',
  before = function(ctx)
    if isBridge(ctx.args[1]) then
      app.log('counter-Athena: refused force-stop of ' .. tostring(ctx.args[1])
              .. ' reason=' .. tostring(ctx.args[5]))
      -- void method: setting a result is what makes the framework skip it.
      ctx.set_result()
    end
  end
}
