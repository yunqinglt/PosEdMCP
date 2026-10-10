/*
 * Deploy's native backend: the one door through which the frontends reach an
 * ELF parser.
 *
 * This is a stub with the same shape the real engine will have. The JNI method
 * nativeAnalyze(op, path, argsJson) returns a JSON string, exactly like the
 * AIDL and MCP layers already speak; swapping this file's body for LIEF calls
 * changes nothing above it. For now only "overview" does real work - it reads
 * the ELF header by hand - and the other ops come back as reserved interfaces.
 */
#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cstdio>
#include <fstream>
#include <string>

#define TAG "DeployNative"

// ---- minimal JSON string building --------------------------------------

static std::string jsonEscape(const std::string& s) {
    std::string out;
    out.reserve(s.size() + 8);
    for (char c : s) {
        switch (c) {
            case '"':  out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) {
                    char buf[8];
                    snprintf(buf, sizeof buf, "\\u%04x", c);
                    out += buf;
                } else {
                    out += c;
                }
        }
    }
    return out;
}

// ---- ELF header fields --------------------------------------------------

static uint16_t u16(const uint8_t* b, bool le) {
    return le ? static_cast<uint16_t>(b[0] | (b[1] << 8))
              : static_cast<uint16_t>((b[0] << 8) | b[1]);
}

static uint32_t u32(const uint8_t* b, bool le) {
    uint32_t v = 0;
    for (int i = 0; i < 4; i++) {
        int shift = le ? i * 8 : (3 - i) * 8;
        v |= static_cast<uint32_t>(b[i]) << shift;
    }
    return v;
}

static uint64_t u64(const uint8_t* b, bool le) {
    uint64_t v = 0;
    for (int i = 0; i < 8; i++) {
        int shift = le ? i * 8 : (7 - i) * 8;
        v |= static_cast<uint64_t>(b[i]) << shift;
    }
    return v;
}

static const char* machine(int m) {
    switch (m) {
        case 183: return "AArch64";
        case 40:  return "ARM";
        case 62:  return "x86_64";
        case 3:   return "x86";
        case 8:   return "MIPS";
        default: {
            static char buf[16];
            snprintf(buf, sizeof buf, "0x%x", m);
            return buf;
        }
    }
}

static const char* eType(int t) {
    switch (t) {
        case 1: return "REL";
        case 2: return "EXEC";
        case 3: return "DYN";
        case 4: return "CORE";
        default: {
            static char buf[16];
            snprintf(buf, sizeof buf, "0x%x", t);
            return buf;
        }
    }
}

// ---- the ops ------------------------------------------------------------

static bool isReserved(const std::string& op) {
    return op == "symbols" || op == "relocations" || op == "strings"
            || op == "metadata" || op == "resolve";
}

static std::string overview(const std::string& path) {
    std::string ep = jsonEscape(path);

    std::ifstream in(path, std::ios::binary | std::ios::ate);
    if (!in) {
        return "{\"path\":\"" + ep + "\",\"exists\":false,\"error\":\"no such file\"}";
    }
    std::streamoff size = in.tellg();
    in.seekg(0, std::ios::beg);

    uint8_t head[64] = {0};
    in.read(reinterpret_cast<char*>(head), sizeof head);
    std::streamsize n = in.gcount();

    std::string out = "{\"path\":\"" + ep + "\",\"exists\":true,\"size\":"
            + std::to_string(static_cast<long long>(size));

    if (n < 20 || head[0] != 0x7f || head[1] != 'E' || head[2] != 'L' || head[3] != 'F') {
        return out + ",\"elf\":false,\"note\":\"not an ELF file\"}";
    }

    int elfClass = head[4]; // EI_CLASS: 1 = 32-bit, 2 = 64-bit
    int elfData = head[5];  // EI_DATA: 1 = little-endian, 2 = big-endian
    bool le = elfData == 1;
    int type = u16(head + 16, le);
    int mach = u16(head + 18, le);
    uint64_t entry = elfClass == 2 ? u64(head + 24, le) : u32(head + 24, le);

    char num[32];
    snprintf(num, sizeof num, "0x%llx", static_cast<unsigned long long>(entry));

    out += ",\"elf\":true";
    out += ",\"class\":\"" + std::string(elfClass == 2 ? "ELF64" : "ELF32") + "\"";
    out += ",\"endianness\":\"" + std::string(elfData == 1 ? "little" : "big") + "\"";
    out += ",\"type\":\"" + std::string(eType(type)) + "\"";
    out += ",\"machine\":\"" + std::string(machine(mach)) + "\"";
    out += ",\"entry\":\"" + std::string(num) + "\"}";
    return out;
}

static std::string reserved(const std::string& op) {
    std::string eo = jsonEscape(op);
    return "{\"backend\":\"stub\",\"op\":\"" + eo + "\",\"implemented\":false,"
            "\"note\":\"op '" + eo + "' is reserved for the LIEF backend and not implemented"
            " in this stub. The interface is fixed; the engine is not built yet.\"}";
}

static std::string analyze(const std::string& op, const std::string& path) {
    if (op == "overview") {
        return overview(path);
    }
    if (isReserved(op)) {
        return reserved(op);
    }
    return "{\"error\":\"unknown op '" + jsonEscape(op)
            + "'. Known ops: overview, symbols, relocations, strings, metadata, resolve\"}";
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_deploy_core_DeployCore_nativeAnalyze(JNIEnv* env, jclass,
        jstring op, jstring path, jstring argsJson) {
    (void) argsJson; // reserved for ops that take arguments
    const char* cOp = op == nullptr ? nullptr : env->GetStringUTFChars(op, nullptr);
    const char* cPath = path == nullptr ? nullptr : env->GetStringUTFChars(path, nullptr);

    std::string result = analyze(cOp == nullptr ? "" : cOp, cPath == nullptr ? "" : cPath);

    if (cOp != nullptr) env->ReleaseStringUTFChars(op, cOp);
    if (cPath != nullptr) env->ReleaseStringUTFChars(path, cPath);
    return env->NewStringUTF(result.c_str());
}
