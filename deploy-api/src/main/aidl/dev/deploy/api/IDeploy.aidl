package dev.deploy.api;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

/**
 * Deploy's analysis interface, shared by the Deploy APK (which implements it)
 * and PosEdMCP (which binds to it).
 *
 * <p>Calls analyze files/buffers or generate instruction bytes; none executes
 * code or accesses another process. JSON replies describe each result/error.
 * The {@code op} string selects the file analysis operation:
 * <ul>
 *   <li>{@code overview} - header, machine, type, entry point</li>
 *   <li>{@code symbols} - dynamic symbol table</li>
 *   <li>{@code relocations} - PLT/GOT relocations</li>
 *   <li>{@code strings} - the string table</li>
 *   <li>{@code metadata} - ELF header, interpreter, dependencies, sections and segments</li>
 *   <li>{@code resolve} - virtual address + file offset for a symbol, for native
 *       injection</li>
 * </ul>
 *
 * <p>{@code disasm} uses Capstone; {@code decompile} uses Ghidra for AArch64.
 * Deploy status reports apiVersion=2; new methods are appended for wire compatibility.
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

    /** Pure assembly: JSON encoded bytes, no execution. Appended to preserve existing transaction IDs. */
    String assemble(String arch, String source, String address);

    /** Bounded immutable byte snapshot, decoded at its original virtual address. Receiver closes its FD. */
    String analyzeBuffer(in ParcelFileDescriptor data, String arch, String address, String argsJson);
}
