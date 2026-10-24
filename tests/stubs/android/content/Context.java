package android.content;
import java.io.File;
import android.content.pm.*;
public abstract class Context {
    public abstract File getFilesDir();
    public abstract ApplicationInfo getApplicationInfo();
    public abstract PackageManager getPackageManager();
}
