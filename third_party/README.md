# Native dependencies moved to Deploy

LIEF and Capstone now belong to the standalone Deploy repository (locally
`E:/deploy`). Pinned commits, the CMake compatibility patch and setup instructions
are maintained in its `third_party/README.md`.

PosEdMCP no longer includes the Deploy or native-fixture Gradle modules and does
not need these dependency trees to build. Existing ignored local checkouts are
left in place as historical build inputs; they are not used by the new Deploy
checkout or committed to either parent repository.
