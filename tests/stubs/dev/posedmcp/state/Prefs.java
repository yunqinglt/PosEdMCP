package dev.posedmcp.state;
import android.content.Context;
public final class Prefs {
    public static final int DEFAULT_BRIDGE_PORT = 8766;
    public static String testToken = "test-token";
    public static int testPort = 8766;
    public static Prefs of(Context context) { return new Prefs(); }
    public String bridgeToken() { return testToken; }
    public int bridgePort() { return testPort; }
}
