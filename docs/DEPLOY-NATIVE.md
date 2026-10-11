# Deploy integration in PosEdMCP

Deploy and its native fixture are maintained in a separate repository, currently
`E:/deploy`. They were extracted from PosEdMCP commit
`7212643ce52ea313cb8bcba43804405f4ca976ef`. PosEdMCP retains its AIDL client,
ConfirmationGate and scoped-process native loader, plus a
[local API contract snapshot](../deploy-api/README.md).

## Build and documentation ownership

Build `:app:assembleDebug` in this repository using the normal PosEdMCP setup.
The only included modules here are `app` and `deploy-api`; a Deploy checkout is
not required. Build `:deploy:assembleDebug :native-fixture:assembleDebug` in the
Deploy repository using its own settings and native dependencies.

The Deploy repository owns these documents and the integration test:

- `README.md`: dependency preparation and standalone builds.
- `docs/MCP_PROMPT.md`: the Deploy agent prompt; PosEdMCP's prompt remains
  [here](MCP_PROMPT.md).
- `docs/DEPLOY-NATIVE.md`: full analysis/loader contract and device verification.
- `tools/deploy-smoke.py`: two-server analysis, scoped loading and isolation checks.
- `MILESTONE-REPORT.md`: historical device results and unverified architectures.

These paths are relative to the Deploy checkout. Its remote has not been
configured; `E:/deploy` is a local location, not a build-time dependency.
Contract changes must be synchronized as described in
[deploy-api/README.md](../deploy-api/README.md).

## Server and privilege boundaries

| Interface | Owner | Behavior |
| --- | --- | --- |
| MCP `127.0.0.1:8765/mcp` | PosEdMCP | Device/root/module tools and Deploy integration |
| MCP `127.0.0.1:4177/mcp` | Deploy | Status, ELF analysis/pseudocode, assembly and byte decoding |
| `dev.deploy.DeployAidlService` | Deploy | Analysis, assembly and read-only library/buffer snapshots |
| `deploy_load_native` | PosEdMCP | Confirmed loading into one exact scoped app peer |
| `deploy_disasm_memory` | PosEdMCP | Confirmed 1–4096-byte target snapshot decoded through AIDL |
| `deploy_patch_memory` | PosEdMCP | Confirmed single ARM64 instruction replacement with expected-byte check |

The apps use separate UIDs and MCP tokens. Deploy is non-root. AIDL analysis
reads with Deploy's file access, even when invoked through PosEdMCP. Missing
Deploy produces a companion error without disabling PosEdMCP's other tools;
Deploy alone can analyze readable `/sdcard/` files without privileged services.

Native loading accepts little-endian arm64 ET_DYN libraries up to 8 MiB, rejects
PIE executables with an interpreter, and targets ordinary scoped applications.
Use the exact online `app:<package>:<pid>` from `module_status`; system_server,
PosEdMCP and Deploy are excluded. Deploy validates a snapshot with LIEF, returns
a read-only PFD and metadata, and PosEdMCP verifies its hash before the existing
plugin confirmation policy. The target verifies the bytes again and uses sealed
memfd plus `android_dlopen_ext(RTLD_NOW | RTLD_LOCAL)` as the target app UID.
There is no automatic root fallback or switch to a different peer.

Constructors execute. The optional entry must be `int64_t function(void)` and is
called on each approved request. `JNI_OnLoad` is not called automatically.
Identical hashes reuse the process's loaded handle, which lasts until exit;
there is no automatic unload or persistent native hook registration. A missing
entry or timeout can occur after loading, so it is not evidence of no execution.
Inspect state before requesting another load.

Deploy 0.4.0 provides Capstone disassembly, native Ghidra 11.3.2 C pseudocode
(little-endian AArch64 only), and Keystone assembly for ARM64/ARM/Thumb/x86/x64.
Decompile selects one symbol/ELF address, budgets 1–20000 instructions (default
4096), and runs in a private Deploy process with a 30-second work deadline.
Types are inferred and import/relocation recovery is incomplete.

`deploy_assemble` and `deploy_analyze_bytes` generate/decode bytes without target
access. AIDL v2 appends these methods and advertises `status.apiVersion=2`.
Live memory uses ASLR-adjusted runtime addresses, not ELF values or file offsets.
The patch tool checks one 4-byte expected instruction in a private RX mapping,
atomically replaces it, flushes instruction cache and restores page protection.
Check `patched` and `permissionsRestored`. Existing execution is not paused;
callers must avoid concurrent mapping unload. No multi-instruction patch or
automatic rollback is provided. Restore with the original source and the new
bytes as `expected`. A timeout/error may follow a write: inspect before retrying.

The real fixture returned 7 → 9 → 7 after patch and restoration, PID 12455,
UID 10289. Five assembly/buffer modes passed roundtrip on the arm64 phone.
Full evidence and commands are in Deploy `MILESTONE-0.4.md` and
`tools/deploy-engines-smoke.py`; raw token-free reports remain local.

Special settings includes ColorOS/HyperOS “帮助Deploy”, disabled by default.
The scripts use the existing ROM-specific cleaner sinks but match dev.deploy and
its colon subprocesses only. Their ownership is independent from original guards;
ordinary clear does not remove special hooks. New dedicated bridge operations
require a newly loaded module in system_server, normally after reboot. Old peers
reject them rather than overwrite an original guard. JVM registry/Lua regression
is available via `tests/run-hooks.ps1`. New system_server behavior and ColorOS
device behavior are not yet verified; no reboot or switch activation was performed.
