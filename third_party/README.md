# Native dependencies moved to Deploy

LIEF and Capstone now belong to the standalone Deploy repository (locally
`E:/deploy`). Pinned commits, the CMake compatibility patch and setup instructions
are maintained in its `third_party/README.md`.

PosEdMCP no longer includes the Deploy or native-fixture Gradle modules and does
not need these dependency trees to build. Existing ignored local checkouts are
left in place as historical build inputs; they are not used by the new Deploy
checkout or committed to either parent repository.

## libsu on the local rootless branch

The rootless shell backend resolves `com.github.topjohnwu.libsu:core:6.0.0` from JitPack.
Upstream: https://github.com/topjohnwu/libsu/tree/8c3e80ffe466b89ff875471e542d77e6a1b480c5
License: Apache-2.0; the upstream license is included as `assets/licenses/libsu.txt` in this APK.
Source checkouts used for inspection remain ignored; no vendored libsu code is built.
