# Deploy native dependencies

Deploy's native backend expects these local source trees:

- `LIEF/` — the ELF parser, with its own `third-party/` dependency archives;
- `capstone/` — reserved for the later `disasm` operation.

The Android SDK installed for this checkout provides CMake 3.22.1. The current
LIEF checkout declares CMake 3.24 as its minimum, although the ELF-only build
used by Deploy configures and compiles with 3.22.1. Keep the local LIEF
`CMakeLists.txt` minimum at 3.22 when preparing the dependency tree for this
SDK.

The parent repository ignores the dependency source trees. They are required
at build time but are intentionally kept outside the PosEdMCP Git history.
