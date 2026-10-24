package dalvik.system;
import java.io.File;
import java.util.List;
public final class DexPathList {
    public Element[] dexElements;
    public List<File> nativeLibraryDirectories;
    public String unrelated = "keep";
    public static final class Element {
        private final File path;
        public Element(String path) { this.path = new File(path); }
    }
}
