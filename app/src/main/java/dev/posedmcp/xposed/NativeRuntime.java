package dev.posedmcp.xposed;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import dev.posedmcp.Logx;

/**
 * Loads this module's native library inside whatever process the module is in.
 *
 * <p>The library ships in the module's own APK, and the process that wants it is
 * a target application, not ours - so the usual route (the app's extracted
 * native library directory) does not exist for it. Two routes are tried:
 *
 * <ol>
 *   <li>Straight out of the module's APK, as {@code <apk>!/lib/<abi>/lib.so}.
 *       Android's linker understands that form and the APK's own file context is
 *       one an application may execute from, so nothing has to be written.</li>
 *   <li>Extracted into the target's cache directory and loaded from there. This
 *       is expected to fail on Android 10 and later, which refuses to execute
 *       code from an application's writable data directory; it is kept because
 *       the failure is worth seeing rather than assuming.</li>
 * </ol>
 *
 * <p>Whatever happened is kept in {@link #status()} - which route worked, or why
 * neither did. A native runtime that silently is not there would be the same
 * class of problem as the smali probe that silently listed nothing.
 */
public final class NativeRuntime {

    private static final String LIBRARY = "libposednative.so";

    private static volatile boolean ready;
    private static volatile String status = "not attempted";
    /** Set by the app over the bridge; see {@link #setModuleApk}. */
    private static volatile String moduleApk;

    private NativeRuntime() {
    }

    /**
     * Tells the module where its own APK is.
     *
     * <p>The module cannot work this out for itself inside a target process: its
     * class loader hands back no code source, and the package manager will not
     * describe another application it cannot see. The app it belongs to knows,
     * so it says so over the connection it already has.
     */
    public static void setModuleApk(String path) {
        if (path != null && !path.isEmpty()) {
            moduleApk = path;
        }
    }

    /** @return {@code null} when the library is usable, otherwise why it is not */
    public static synchronized String ensureLoaded() {
        if (ready) {
            return null;
        }
        if (status.startsWith("could not")) {
            return status;
        }

        String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "arm64-v8a";
        String apk = ownApkPath();

        if (apk != null) {
            String entry = apk + "!/lib/" + abi + "/" + LIBRARY;
            try {
                System.load(entry);
                ready = true;
                status = "loaded from the module APK: " + entry;
                Logx.i("native runtime " + status);
                return null;
            } catch (Throwable t) {
                status = "load from the APK failed (" + t.getClass().getSimpleName() + ": "
                        + t.getMessage() + ")";
                Logx.w("native runtime: " + status);
            }
        } else {
            status = "could not find the module APK path";
            Logx.w("native runtime: " + status);
        }

        String extracted = extractAndLoad(apk, abi);
        if (extracted == null) {
            ready = true;
            return null;
        }
        status = "could not load the native library - " + status + "; and " + extracted;
        return status;
    }

    /** @return {@code null} on success, otherwise why it failed */
    private static String extractAndLoad(String apk, String abi) {
        Context context = AppHost.currentApplication();
        if (context == null || apk == null) {
            return "nothing to extract from";
        }
        File out = new File(context.getCacheDir(), LIBRARY);
        try (ZipFile zip = new ZipFile(apk);
                InputStream in = zip.getInputStream(new ZipEntry("lib/" + abi + "/" + LIBRARY));
                FileOutputStream stream = new FileOutputStream(out)) {
            if (in == null) {
                return "the APK has no lib/" + abi + "/" + LIBRARY;
            }
            byte[] buffer = new byte[16384];
            int read;
            while ((read = in.read(buffer)) > 0) {
                stream.write(buffer, 0, read);
            }
        } catch (Throwable t) {
            return "extraction failed (" + t + ")";
        }
        try {
            System.load(out.getAbsolutePath());
            status = "loaded from " + out.getAbsolutePath();
            Logx.i("native runtime " + status);
            return null;
        } catch (Throwable t) {
            return "load from the cache failed (" + t.getClass().getSimpleName() + ": "
                    + t.getMessage() + ")";
        }
    }

    /** The path of the APK this class was loaded from, or {@code null}. */
    private static String ownApkPath() {
        String declared = moduleApk;
        if (declared != null && declared.endsWith(".apk")) {
            return declared;
        }
        try {
            java.security.CodeSource source =
                    NativeRuntime.class.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                String path = source.getLocation().getPath();
                if (path != null && path.endsWith(".apk")) {
                    return path;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Which route worked, or why none did. Shown to the agent. */
    public static String status() {
        return status;
    }

    public static boolean isReady() {
        return ready;
    }

    // ---- the library -----------------------------------------------------

    static native String probe();

    static native String lastError();

    /**
     * A small id for the loaded library, not its handle.
     *
     * <p>A dlopen handle is not always an address on this platform - a library in
     * a non-default namespace gets a synthetic value the linker resolves through
     * an internal map - and sending one out to Java and Lua and back left dlsym
     * faulting inside the linker. The id never leaves the native side.
     */
    static native int openLibrary(String path);

    /** Sealed in-memory ELF, loaded with android_dlopen_ext; JSON with handleId or error. */
    static native String loadSnapshot(byte[] bytes, String sha256);

    static native long findSymbol(int id, String name);

    static native long call(long address, int arity, long a0, long a1, long a2, long a3, long a4,
            long a5);

    static native byte[] readMemory(long address, int length);

    static native boolean writeMemory(long address, byte[] bytes);
    static native byte[] readSnapshot(long address, int length) throws java.io.IOException;
    static native String patchInstruction(long address, byte[] expected, byte[] replacement);
}
