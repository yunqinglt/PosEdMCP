package dev.posedmcp.state;
import android.content.Context;
import java.util.*;
public final class HookStore {
    public static final List<SavedHook> testHooks = new ArrayList<>();
    public static HookStore of(Context context) { return new HookStore(); }
    public List<SavedHook> all() { return new ArrayList<>(testHooks); }
}
