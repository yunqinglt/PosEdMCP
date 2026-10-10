package dev.deploy.core;

import android.content.Context;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import org.json.JSONObject;
import dev.deploy.api.DeployContract;

/** An unlinked read-only snapshot: LIEF validates the very bytes the caller receives. */
public final class LibrarySnapshot {
    private LibrarySnapshot() {}

    public static ParcelFileDescriptor prepare(Context context, DeployCore core, String path,
            Bundle result) throws Exception {
        File source = new File(path).getCanonicalFile();
        if (!source.isFile()) throw new IllegalArgumentException("not a regular file");
        File snapshot = File.createTempFile("deploy-library-", ".so", context.getCacheDir());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            int total = 0;
            try (FileInputStream in = new FileInputStream(source);
                    FileOutputStream out = new FileOutputStream(snapshot)) {
                byte[] buffer = new byte[32768];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    total += n;
                    if (total > DeployContract.MAX_LIBRARY_BYTES)
                        throw new IllegalArgumentException("native library exceeds 8 MiB transport limit");
                    out.write(buffer, 0, n);
                    digest.update(buffer, 0, n);
                }
            }
            JSONObject info = core.analyze(snapshot.getPath(), "overview", null);
            if (!info.optBoolean("elf") || !"DYN".equals(info.optString("type")))
                throw new IllegalArgumentException("injection requires an ELF shared object (ET_DYN)");
            if (!"AArch64".equals(info.optString("machine")) || !"ELF64".equals(info.optString("class"))
                    || !"little".equals(info.optString("endianness")))
                throw new IllegalArgumentException("native loading currently requires little-endian arm64 ELF");
            JSONObject metadata = core.analyze(snapshot.getPath(), "metadata", null);
            if (!metadata.optString("interpreter", "").isEmpty())
                throw new IllegalArgumentException("PIE executables cannot be loaded as native libraries");
            StringBuilder hash = new StringBuilder();
            for (byte b : digest.digest()) hash.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            info.put("path", source.getPath()).put("sha256", hash.toString())
                    .put("needed", metadata.getJSONArray("needed"));
            result.putString("metadata", info.toString());
            // Return the PFD directly: AIDL forwards RETURN_VALUE so Binder closes
            // our copy. Nesting it inside a Bundle loses that flag and leaks FDs.
            return ParcelFileDescriptor.open(snapshot, ParcelFileDescriptor.MODE_READ_ONLY);
        } finally {
            // The read-only descriptor keeps the inode alive until the receiver closes it.
            snapshot.delete();
        }
    }
}
