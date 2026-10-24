local function call(obj, name, ...) return app.call(obj, name, ...) end
local function cls(name) return app.class(name) end
local ctx = app.context()
local battery = call(ctx, "getSystemService", "batterymanager")
local power = call(ctx, "getSystemService", "power")
local conn = call(ctx, "getSystemService", "connectivity")
local checks = {}
checks.battery_level = call(battery, "getIntProperty", 4) == 37
checks.battery_status = call(battery, "getIntProperty", 6) == 3
checks.battery_not_charging = call(battery, "isCharging") == false
local intent = app.new("android.content.Intent", "android.intent.action.BATTERY_CHANGED")
checks.battery_intent = call(intent, "getIntExtra", "level", -1) == 37 and call(intent, "getIntExtra", "scale", -1) == 100 and call(intent, "getIntExtra", "plugged", -1) == 0
checks.locale = call(call(cls("java.util.Locale"), "getDefault()"), "toLanguageTag") == "en-US"
checks.locale_list = call(call(cls("android.os.LocaleList"), "getDefault"), "toLanguageTags") == "en-US"
local config = call(call(ctx, "getResources"), "getConfiguration")
checks.resource_locale = call(call(config, "getLocales"), "toLanguageTags") == "en-US"
checks.time_zone = call(call(cls("java.util.TimeZone"), "getDefault"), "getID") == "Pacific/Honolulu"
checks.power_save = call(power, "isPowerSaveMode") == true
checks.interactive = call(power, "isInteractive") == true
checks.device_idle = call(power, "isDeviceIdleMode") == false
checks.network_metered = call(conn, "isActiveNetworkMetered") == true
local caps = app.new("android.net.NetworkCapabilities")
checks.network_capabilities = call(caps, "hasCapability", 16) == true and call(caps, "hasCapability", 12) == true and call(caps, "hasCapability", 11) == false
checks.network_transport = call(caps, "hasTransport", 0) == true and call(caps, "hasTransport", 1) == false
local location = app.new("android.location.Location", "gps")
checks.location_getters = math.abs(call(location, "getLatitude") - 35.681236) < 0.000001 and math.abs(call(location, "getLongitude") - 139.767125) < 0.000001 and call(location, "getAccuracy") == 12
local count = 0
for key, value in pairs(checks) do print(key .. "=" .. tostring(value)); if value then count = count + 1 end end
print("passed=" .. count .. "/15")
return checks
