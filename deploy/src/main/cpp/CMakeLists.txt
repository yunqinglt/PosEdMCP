cmake_minimum_required(VERSION 3.22.1)

project(deploynative C CXX)

set(DEPLOY_THIRD_PARTY_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party")
get_filename_component(DEPLOY_THIRD_PARTY_DIR "${DEPLOY_THIRD_PARTY_DIR}" ABSOLUTE)
if(NOT EXISTS "${DEPLOY_THIRD_PARTY_DIR}/LIEF/CMakeLists.txt")
    message(FATAL_ERROR
        "Deploy needs third_party/LIEF. See third_party/README.md for the local dependency setup.")
endif()

# Deploy only needs LIEF's ELF reader. Keep the other format modules and build
# products out of the Android build; this also keeps the static link small.
set(LIEF_TESTS OFF CACHE BOOL "" FORCE)
set(LIEF_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LIEF_INSTALL OFF CACHE BOOL "" FORCE)
set(LIEF_PYTHON_API OFF CACHE BOOL "" FORCE)
set(LIEF_RUST_API OFF CACHE BOOL "" FORCE)
set(LIEF_ENABLE_JSON OFF CACHE BOOL "" FORCE)
set(LIEF_LOGGING OFF CACHE BOOL "" FORCE)
set(LIEF_PE OFF CACHE BOOL "" FORCE)
set(LIEF_COFF OFF CACHE BOOL "" FORCE)
set(LIEF_MACHO OFF CACHE BOOL "" FORCE)
set(LIEF_DEX OFF CACHE BOOL "" FORCE)
set(LIEF_ART OFF CACHE BOOL "" FORCE)
set(LIEF_OAT OFF CACHE BOOL "" FORCE)
set(LIEF_VDEX OFF CACHE BOOL "" FORCE)
set(LIEF_DEBUG_INFO OFF CACHE BOOL "" FORCE)
set(LIEF_OBJC OFF CACHE BOOL "" FORCE)
set(LIEF_DYLD_SHARED_CACHE OFF CACHE BOOL "" FORCE)
set(LIEF_ASM OFF CACHE BOOL "" FORCE)
set(LIEF_USE_MELKOR OFF CACHE BOOL "" FORCE)
add_subdirectory("${DEPLOY_THIRD_PARTY_DIR}/LIEF" "${CMAKE_CURRENT_BINARY_DIR}/lief")

if(NOT EXISTS "${DEPLOY_THIRD_PARTY_DIR}/capstone/CMakeLists.txt")
    message(FATAL_ERROR "Deploy needs third_party/capstone. See third_party/README.md.")
endif()
set(CAPSTONE_ARCHITECTURE_DEFAULT OFF CACHE BOOL "" FORCE)
set(CAPSTONE_AARCH64_SUPPORT ON CACHE BOOL "" FORCE)
set(CAPSTONE_ARM_SUPPORT ON CACHE BOOL "" FORCE)
set(CAPSTONE_X86_SUPPORT ON CACHE BOOL "" FORCE)
set(CAPSTONE_BUILD_CSTOOL OFF CACHE BOOL "" FORCE)
set(CAPSTONE_BUILD_CSTEST OFF CACHE BOOL "" FORCE)
set(CAPSTONE_BUILD_LEGACY_TESTS OFF CACHE BOOL "" FORCE)
set(CAPSTONE_INSTALL OFF CACHE BOOL "" FORCE)
add_subdirectory("${DEPLOY_THIRD_PARTY_DIR}/capstone" "${CMAKE_CURRENT_BINARY_DIR}/capstone")

# The analysis backend. LIEF is statically linked into this JNI library so the
# Java/AIDL/MCP seam remains unchanged when more ELF operations are added.
add_library(deploynative SHARED deploynative.cpp)

target_compile_features(deploynative PRIVATE cxx_std_17)
target_link_libraries(deploynative PRIVATE LIEF::LIEF capstone_static)
target_include_directories(deploynative PRIVATE "${DEPLOY_THIRD_PARTY_DIR}/capstone/include")

find_library(log-lib log)
target_link_libraries(deploynative PRIVATE ${log-lib})
