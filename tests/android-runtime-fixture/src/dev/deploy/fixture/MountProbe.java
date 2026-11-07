package dev.deploy.fixture;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** The child stays native; the application reads its stdout in the parent. */
final class MountProbe {
    private static final String PATH_MARKER = "/debug_ramdisk/.magisk/fixture";
    private static final String MOUNT = "/system/bin/mount";

    static JSONObject collect() throws Exception {
        JSONObject out = new JSONObject();
        String savedPath = Results.swapPath("/system/bin:" + PATH_MARKER + ":/sbin");
        try {
            String path = System.getenv("PATH");
            out.put("pathMarker", path != null && path.contains(PATH_MARKER))
                    .put("ordinaryPath", path != null && path.startsWith("/system/bin") && path.endsWith("/sbin"));
        } finally { Results.swapPath(savedPath); }
        String key = "magisk.version", original = System.setProperty(key, "fixture");
        try {
            out.put("propertyMarker", "fixture".equals(System.getProperty(key)))
                    .put("propertyDefault", System.getProperty(key, "fallback"))
                    .put("propertyRaw", System.getProperties().getProperty(key))
                    .put("ordinaryProperty", System.getProperty("java.vm.name"));
        } finally {
            if (original == null) System.clearProperty(key); else System.setProperty(key, original);
        }
        out.put("nioMounts", lines(Files.readAllLines(Paths.get("/proc/self/mounts"))));
        out.put("nioMountInfo", lines(Files.readAllLines(Paths.get("/proc/self/mountinfo"))));
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/mounts"))) {
            out.put("readerMounts", lines(reader.lines().collect(java.util.stream.Collectors.toList())));
        }
        try (RandomAccessFile file = new RandomAccessFile("/proc/self/mounts", "r")) {
            ArrayList<String> rows = new ArrayList<>();
            for (String row; (row = file.readLine()) != null;) rows.add(row);
            out.put("rafMounts", lines(rows));
        }
        Runtime runtime = Runtime.getRuntime();
        JSONArray calls = new JSONArray();
        calls.put(child(runtime.exec(MOUNT)));
        calls.put(child(runtime.exec(MOUNT, null)));
        calls.put(child(runtime.exec(MOUNT, null, new File("/"))));
        calls.put(child(runtime.exec(new String[]{MOUNT})));
        calls.put(child(runtime.exec(new String[]{MOUNT}, null)));
        calls.put(child(runtime.exec(new String[]{MOUNT}, null, new File("/"))));
        out.put("exec", calls);
        out.put("shellMount", child(runtime.exec(new String[]{"/system/bin/sh", "-c", "mount"})));
        out.put("shellCat", child(runtime.exec(new String[]{"/system/bin/sh", "-c", "cat /proc/self/mounts"})));
        out.put("builderCat", child(new ProcessBuilder("/system/bin/toybox", "cat", "/proc/self/mountinfo").start()));
        out.put("ordinaryExec", child(runtime.exec(new String[]{"/system/bin/sh", "-c", "printf ordinary; exit 23"})));
        out.put("nativeMount", nativeOutput(Results.systemOutput("mount")));
        out.put("nativeAbsolute", nativeOutput(Results.systemOutput(MOUNT)));
        out.put("nativeOrdinary", nativeOutput(Results.systemOutput("printf ordinary; exit 23")));
        return out;
    }

    private static JSONObject child(Process child) throws Exception {
        InputStream stdout = child.getInputStream();
        String text = new String(stdout.readAllBytes(), StandardCharsets.UTF_8);
        int errors = child.getErrorStream().readAllBytes().length;
        return lines(Arrays.asList(text.split("\n"))).put("exit", child.waitFor())
                .put("filtered", stdout.getClass().getName().equals("dev.posedmcp.xposed.PackageListFilter"))
                .put("processClass", child.getClass().getName()).put("stderrBytes", errors)
                .put("ordinary", text.equals("ordinary"));
    }

    private static JSONObject nativeOutput(String output) throws Exception {
        int split = output.indexOf('\n');
        String stdout = output.substring(split + 1);
        return lines(Arrays.asList(stdout.split("\n")))
                .put("waitStatus", Integer.parseInt(output.substring(0, split))).put("ordinary", stdout.equals("ordinary"));
    }

    private static JSONObject lines(List<String> rows) throws Exception {
        int markers = 0, ordinary = 0;
        for (String row : rows) {
            if (row.contains("magisk") || row.contains("zygisk_lsposed") || row.contains("/data/adb/lspd")) markers++;
            else if (!row.isEmpty()) ordinary++;
        }
        return new JSONObject().put("markers", markers).put("ordinaryRows", ordinary);
    }
}
