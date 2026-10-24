package fixture;

import android.content.Context;
import android.content.pm.*;
import android.os.Bundle;
import dev.posedmcp.ipc.BridgeBootstrap;
import dev.posedmcp.state.*;
import java.io.*;
import java.nio.file.*;
import org.json.*;

/** Production snapshot reader/writer: no Android SharedPreferences cache in the read path. */
public final class BootstrapRegression {
    static int assertions;
    static void check(boolean ok, String message) { assertions++; if (!ok) throw new AssertionError(message); }
    static SavedHook hook(String pkg, boolean enabled) {
        SavedHook hook = new SavedHook(); hook.packageName = pkg; hook.enabled = enabled;
        hook.className = "fixture.Target"; hook.methodName = "run"; return hook;
    }
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory(Path.of(".tools"), "bootstrap-test-");
        Context context = new Context() {
            public File getFilesDir() { return dir.toFile(); }
            public ApplicationInfo getApplicationInfo() { ApplicationInfo info = new ApplicationInfo(); info.sourceDir = "/data/app/module/base.apk"; return info; }
            public PackageManager getPackageManager() { return new PackageManager() {
                public String[] getPackagesForUid(int uid) { return switch(uid) {
                    case 10123, 110123 -> new String[]{"fixture.target"};
                    case 10124 -> new String[]{"fixture.target", "fixture.shared"};
                    case 1000 -> new String[]{"android"};
                    default -> null;
                }; }
            }; }
        };
        HookStore.testHooks.add(hook("fixture.target", true));
        HookStore.testHooks.add(hook("fixture.shared", true));
        HookStore.testHooks.add(hook("fixture.disabled", false));
        HookStore.testHooks.add(hook("android", true));
        BridgeBootstrap.publish(context);
        Bundle first = BridgeBootstrap.read(context, 10123);
        check(first.getString("bridge_token").equals("test-token"), "published token");
        check(new JSONArray(first.getString(HookMirror.KEY)).length() == 1, "only requesting UID package");
        check(new JSONArray(BridgeBootstrap.read(context, 1000).getString(HookMirror.KEY)).isEmpty(), "exclude system hooks");
        check(new JSONArray(BridgeBootstrap.read(context, 99999).getString(HookMirror.KEY)).isEmpty(), "unknown UID gets no hooks");
        check(new JSONArray(BridgeBootstrap.read(context, 110123).getString(HookMirror.KEY)).length() == 1, "secondary user UID");
        check(new JSONArray(BridgeBootstrap.read(context, 10124).getString(HookMirror.KEY)).length() == 2, "shared UID packages");
        HookStore.testHooks.clear();
        Prefs.testToken = "new-token"; Prefs.testPort = 9988;
        RuntimePolicy.testPolicy.put("concealment", false);
        BridgeBootstrap.publish(context);
        Bundle fresh = BridgeBootstrap.read(context, 10123);
        check(fresh.getString("bridge_token").equals("new-token") && fresh.getInt("bridge_port") == 9988, "read fresh generation without process restart");
        check(new JSONArray(fresh.getString(HookMirror.KEY)).isEmpty(), "removed hooks cannot survive in process cache");
        check(!new JSONObject(fresh.getString(RuntimePolicy.KEY)).getBoolean("concealment"), "updated runtime policy");
        check(fresh.getString("module_apk").endsWith("base.apk"), "native APK path available at bootstrap");
        Prefs.testToken = "";
        BridgeBootstrap.publish(context);
        check(BridgeBootstrap.read(context, 10123).getString("bridge_token").equals("new-token"), "do not overwrite snapshot with empty credentials");
        Files.writeString(dir.resolve("bridge-bootstrap.json"), "corrupt");
        check(!BridgeBootstrap.read(context, 10123).containsKey(HookMirror.KEY), "corrupt data cannot rearm stale hooks");
        System.out.println("PASS bootstrap snapshot (" + assertions + " assertions)");
    }
}
