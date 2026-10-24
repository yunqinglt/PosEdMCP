package android.util;
import java.io.*;
import java.nio.file.*;
/** Mimics publication only; Android's own AtomicFile is verified by platform tests. */
public final class AtomicFile {
    private final File target, next;
    public AtomicFile(File target) { this.target = target; this.next = new File(target + ".new"); }
    public FileOutputStream startWrite() throws IOException { return new FileOutputStream(next); }
    public void finishWrite(FileOutputStream stream) throws IOException {
        stream.close(); Files.move(next.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
    public void failWrite(FileOutputStream stream) { try { stream.close(); } catch (IOException ignored) {} next.delete(); }
}
