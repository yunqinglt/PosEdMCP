package android.os;
import java.util.HashMap;
public final class Bundle {
    private final HashMap<String,Object> entries = new HashMap<>();
    public void putString(String key, String value) { entries.put(key, value); }
    public void putInt(String key, int value) { entries.put(key, value); }
    public String getString(String key) { return (String)entries.get(key); }
    public int getInt(String key) { return entries.containsKey(key) ? (Integer)entries.get(key) : 0; }
    public boolean containsKey(String key) { return entries.containsKey(key); }
}
