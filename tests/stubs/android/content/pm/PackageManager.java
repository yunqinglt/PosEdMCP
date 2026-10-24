package android.content.pm;
public abstract class PackageManager {
    public abstract String[] getPackagesForUid(int uid);
    public static class NameNotFoundException extends Exception {
        public NameNotFoundException(String name) { super(name); }
    }
}
