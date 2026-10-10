package dev.deploy.api;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

/**
 * Deploy's analysis interface, shared by the Deploy APK (which implements it)
 * and PosEdMCP (which binds to it).
 *
 * <p>Every call is read-only: it parses a shared object already on disk and
 * returns JSON as a string, the same convention {@code DexService} uses for its
 * Binder payloads. The {@code op} string selects the layer the full LIEF engine
 * will offer:
 * <ul>
 *   <li>{@code overview} - header, machine, type, entry point</li>
 *   <li>{@code symbols} - dynamic and regular symbol tables</li>
 *   <li>{@code relocations} - PLT/GOT relocations</li>
 *   <li>{@code strings} - the string table</li>
 *   <li>{@code metadata} - build-id, notes, hardening hints</li>
 *   <li>{@code resolve} - virtual address + file offset for a symbol, for native
 *       injection</li>
 * </ul>
 *
 * <p>{@code disasm} uses Capstone; {@code decompile} reports unavailable until an engine is installed.
 */
interface IDeploy {

    /** A one-line identity: name, version, backend. Never fails. */
    String ping();

    /** JSON: backend version, ABI, and the list of implemented ops. */
    String status();

    /** JSON result for one op against one file. Errors come back as JSON too. */
    String analyze(String path, String op, String argsJson);

    /** Read-only snapshot: descriptor + LIEF metadata. Does not load or execute the library. */
    ParcelFileDescriptor prepareLibrary(String path, out Bundle metadata);
}
