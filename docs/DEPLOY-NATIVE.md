# Deploy analysis and native loading

Deploy 0.3.0 runs LIEF + Capstone on the device. The standalone MCP endpoint
(`127.0.0.1:4177`), PosEdMCP's AIDL proxy, and the future frontend share the same
analysis core. The applications have separate UIDs and preferences; no shared UID
or root capability is added to Deploy.

## Disassembly

Call `deploy_analyze` on either server:

```json
{"path":"/sdcard/libc.so","op":"disasm","name":"strlen","max":16}
```

Supply exactly one of `name` and `address`. Addresses are hexadecimal strings in
the ELF virtual address space, **before ASLR**, not file offsets or runtime
addresses. Results contain each instruction's ELF address, file offset, bytes,
size, mnemonic and operands. `nextAddress` and `stopReason` describe continuation.
An IFUNC symbol describes its resolver, not the implementation selected at runtime.

- `max`: instruction count, default 64, range 1–512.
- `bytes`: byte window, range 1–4096; defaults to the symbol size (capped at 4096),
  or 256 when no symbol size is available. Symbol and file-backed executable
  segment boundaries cap the window.
- Supported ELF architectures: AArch64, ARM/Thumb, x86 and x86_64, little-endian.
  The APK itself is arm64. For ARM32 `mode=auto` uses the symbol/address low bit;
  use `mode=thumb` for a raw even Thumb address or `mode=arm` for ARM instructions.
- Unmapped, misaligned, undefined or non-function addresses/symbols produce an
  error. No instruction is fabricated when decoding stops.
- `op=decompile` is reserved for a future C pseudocode engine. It returns
  `DECOMPILER_UNAVAILABLE`; it does not label assembly as pseudocode.

The shared `DeployContract` keeps both MCP input schemas aligned, including
`name`, `address`, `max`, `bytes`, and `mode`. These arguments are also available
through AIDL. Native errors set the MCP tool error flag.

## Runtime loading through PosEdMCP

Only PosEdMCP advertises `deploy_load_native`. Select the exact online peer from
`module_status`; the target must be an ordinary application in the framework's
scope, started since the updated module was installed.

```json
{
  "package":"dev.deploy.fixture",
  "peer":"app:dev.deploy.fixture:12345",
  "path":"/sdcard/deploy-tests/libdeploy_fixture.so",
  "entry":"deploy_fixture_probe",
  "reason":"Load the test library and return the PID captured by its constructor"
}
```

Replace the peer key with the live value. The flow is:

1. Deploy copies a readable regular file into a bounded snapshot, validates its
   ELF with LIEF, and calculates SHA-256. It returns metadata and a read-only file
   descriptor over AIDL; the temporary pathname is unlinked. Maximum size: 8 MiB.
2. PosEdMCP reads and verifies that snapshot before confirmation. The normal
   `ConfirmationGate.Kind.PLUGIN` policy displays the package, exact process,
   source path, length, hash, entry and reason. No execution occurs in Deploy.
3. After approval the exact peer must still be online. PosEdMCP transports the
   approved bytes over its existing bridge; the target checks ELF and SHA-256
   again. There is no automatic retargeting to another process or root fallback.
4. In the scoped application, `libposednative.so` creates a sealed memfd and calls
   `android_dlopen_ext(RTLD_NOW | RTLD_LOCAL)`. ELF constructors run as that app's
   UID. Android linker namespace/dependency and SELinux restrictions still apply;
   a loader failure is returned, not bypassed.

This milestone accepts little-endian arm64 ET_DYN shared objects; PIE executables
with an interpreter are rejected. System framework, PosEdMCP and Deploy are not
targets of this new tool. `entry` is optional; when present it must be an exported
**`int64_t function(void)`**. It is called on each approved request. `JNI_OnLoad`
is not called automatically. A missing entry explicitly reports that the library
has already been loaded. A timeout/crash can occur after constructors ran: inspect
target state before requesting another load.

Libraries are deduplicated by SHA-256 within a target process. They remain loaded
until process exit; there is no unsafe automatic `dlclose` and no persisted hook
registration. Changed code requires a new hash or a process restart. Required
libraries must be available in the target's existing linker namespace.

This provides runtime native code loading. It does not yet implement persistent
native hooks, inline/PLT trampolines, or an offline ELF rewriting/deployment tool.
LIEF's [ELF Hooking tutorial](https://lief.re/doc/latest/tutorials/04_elf_hooking.html)
adds code segments and changes symbols in an output file; that is a separate
workflow. The [Runtime module](https://lief.re/doc/latest/runtime/intro.html)
operates in the process hosting the library and does not itself grant Deploy
access to a scoped application's process.

## Device verification

Build `:deploy:assembleDebug :app:assembleDebug :native-fixture:assembleDebug`.
The fixture is a separate optional test APK, not included in either product APK.
Install all three, put `libdeploy_fixture.so` from the fixture APK's
`lib/arm64-v8a/` directory at `/sdcard/deploy-tests/`, and retain `/sdcard/libc.so`
as a real-world sample. Start both MCP services.

```text
python tools/deploy-smoke.py --serial 3972b041 --output E:/deploy/smoke-analysis.json
python tools/deploy-smoke.py --serial 3972b041 --output E:/deploy/smoke-runtime.json --load-fixture
python tools/deploy-smoke.py --serial 3972b041 --output E:/deploy/smoke-isolation.json --isolation
```

For `--load-fixture`, enable **Deploy Native Fixture** in PosEdMCP's LSPosed/Vector
scope, restart it and approve the ordinary application prompts when shown. The
Activity has no loader/bridge of its own. The fixture's ELF constructor captures
its process PID; the entry returns that captured value. The test compares it with
the selected peer and checks a non-root UID. It also checks handle reuse and an
entry returning 42, explicit missing-entry errors and offline target rejection.

Tokens are kept in memory, never written to the JSON reports. Forwarding uses
host ports 14177 and 18765; no other adb forwarding rules are removed.

Verified on Xiaomi 24129PN74C (Android 15, arm64): direct MCP and AIDL disassembly
including libc's `strlen`, error cases, and real scoped-process native loading.
The first load returned PID 4416 from the constructor, matching the bridge peer,
with UID 10289 and `loader=android_dlopen_ext_memfd`.

The final descriptor ownership fix was also tested on the device, along with
handle reuse, missing entry reporting and both isolated server configurations.
Both apps were restored to their original enabled state and their services were
restarted. On this ROM starting an unexported service immediately after restoring
a stopped package can report "not found"; the test launches its Activity first.

AArch64 is the architecture tested end to end. ARM/Thumb/x86/x64 decoders compile,
but the supplementary device test timed out and its retry was rejected by the
execution approval system ("blocked by policy", including after user approval).
Those four targets remain unverified on the device. An occasional background
HTTP timeout was observed; it was not diagnosed as a parser or loader failure.
