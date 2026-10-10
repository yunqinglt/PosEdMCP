/*
 * Deploy's native ELF backend.
 *
 * The JNI method is the stable seam shared by the AIDL and MCP frontends. LIEF
 * owns parsing; this file turns the selected ELF view into the small JSON
 * protocol used above it. JSON is deliberately built here instead of enabling
 * LIEF's optional JSON dependency, which keeps the Android binary smaller.
 */
#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <cctype>
#include <cstdio>
#include <fstream>
#include <memory>
#include <string>
#include <string_view>
#include <stdexcept>

#include <LIEF/ELF.hpp>
#include <capstone/capstone.h>

#define TAG "DeployNative"

namespace {

using LIEF::ELF::Binary;
using LIEF::ELF::DynamicEntry;
using LIEF::ELF::DynamicEntryLibrary;
using LIEF::ELF::Parser;
using LIEF::ELF::Relocation;
using LIEF::ELF::Section;
using LIEF::ELF::Segment;
using LIEF::ELF::Symbol;

constexpr size_t DEFAULT_LIMIT = 2000;
constexpr size_t HARD_LIMIT = 20000;

static std::string jsonEscape(std::string_view value) {
    std::string out;
    out.reserve(value.size() + 8);
    for (unsigned char c : value) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    snprintf(buf, sizeof buf, "\\u%04x", c);
                    out += buf;
                } else {
                    out += static_cast<char>(c);
                }
        }
    }
    return out;
}

static std::string jsonString(std::string_view value) {
    return "\"" + jsonEscape(value) + "\"";
}

static std::string hex(uint64_t value) {
    char buf[32];
    snprintf(buf, sizeof buf, "0x%llx", static_cast<unsigned long long>(value));
    return buf;
}

static const char* elfClassName(LIEF::ELF::Header::CLASS value) {
    switch (value) {
        case LIEF::ELF::Header::CLASS::ELF32: return "ELF32";
        case LIEF::ELF::Header::CLASS::ELF64: return "ELF64";
        default: return "unknown";
    }
}

static const char* endianName(LIEF::ELF::Header::ELF_DATA value) {
    switch (value) {
        case LIEF::ELF::Header::ELF_DATA::LSB: return "little";
        case LIEF::ELF::Header::ELF_DATA::MSB: return "big";
        default: return "unknown";
    }
}

static std::string machineName(LIEF::ELF::ARCH value) {
    switch (value) {
        case LIEF::ELF::ARCH::AARCH64: return "AArch64";
        case LIEF::ELF::ARCH::ARM: return "ARM";
        case LIEF::ELF::ARCH::X86_64: return "x86_64";
        case LIEF::ELF::ARCH::I386: return "x86";
        case LIEF::ELF::ARCH::MIPS: return "MIPS";
        default: return LIEF::ELF::to_string(value);
    }
}

static size_t jsonLimit(std::string_view args) {
    const size_t key = args.find("\"max\"");
    if (key == std::string_view::npos) return DEFAULT_LIMIT;
    const size_t colon = args.find(':', key + 5);
    if (colon == std::string_view::npos) return DEFAULT_LIMIT;
    size_t begin = colon + 1;
    while (begin < args.size() && std::isspace(static_cast<unsigned char>(args[begin]))) ++begin;
    size_t end = begin;
    while (end < args.size() && std::isdigit(static_cast<unsigned char>(args[end]))) ++end;
    if (end == begin) return DEFAULT_LIMIT;
    try {
        return std::clamp<size_t>(std::stoull(std::string(args.substr(begin, end - begin))),
                                  1, HARD_LIMIT);
    } catch (...) {
        return DEFAULT_LIMIT;
    }
}

static std::string jsonArgument(std::string_view args, std::string_view wanted) {
    const std::string key = "\"" + std::string(wanted) + "\"";
    const size_t at = args.find(key);
    if (at == std::string_view::npos) return {};
    const size_t colon = args.find(':', at + key.size());
    if (colon == std::string_view::npos) return {};
    size_t begin = colon + 1;
    while (begin < args.size() && std::isspace(static_cast<unsigned char>(args[begin]))) ++begin;
    if (begin >= args.size() || args[begin] != '"') return {};
    ++begin;
    std::string value;
    bool escaped = false;
    for (size_t i = begin; i < args.size(); ++i) {
        const char c = args[i];
        if (escaped) {
            switch (c) {
                case 'n': value += '\n'; break;
                case 'r': value += '\r'; break;
                case 't': value += '\t'; break;
                default: value += c; break;
            }
            escaped = false;
        } else if (c == '\\') {
            escaped = true;
        } else if (c == '"') {
            break;
        } else {
            value += c;
        }
    }
    return value;
}

static std::unique_ptr<const Binary> parseBinary(const std::string& path) {
    return std::unique_ptr<const Binary>{Parser::parse(path)};
}

static bool fileSize(const std::string& path, uint64_t* size) {
    std::ifstream in(path, std::ios::binary | std::ios::ate);
    if (!in) return false;
    const std::streamoff end = in.tellg();
    if (end < 0) return false;
    *size = static_cast<uint64_t>(end);
    return true;
}

static std::string commonPrefix(const std::string& path, uint64_t size) {
    return "{\"path\":" + jsonString(path) + ",\"exists\":true,\"size\":"
            + std::to_string(size);
}

static std::string overview(const std::string& path) {
    uint64_t size = 0;
    if (!fileSize(path, &size)) {
        return "{\"path\":" + jsonString(path)
                + ",\"exists\":false,\"error\":\"no such file or unreadable\"}";
    }
    const auto binary = parseBinary(path);
    if (!binary) {
        return commonPrefix(path, size)
                + ",\"elf\":false,\"note\":\"LIEF could not parse this file\"}";
    }
    const auto& header = binary->header();
    std::string out = commonPrefix(path, size);
    out += ",\"elf\":true";
    out += ",\"class\":" + jsonString(elfClassName(header.identity_class()));
    out += ",\"endianness\":" + jsonString(endianName(header.identity_data()));
    out += ",\"type\":" + jsonString(LIEF::ELF::to_string(header.file_type()));
    out += ",\"machine\":" + jsonString(machineName(header.machine_type()));
    out += ",\"entry\":" + jsonString(hex(header.entrypoint()));
    out += ",\"sections\":" + std::to_string(header.numberof_sections());
    out += ",\"segments\":" + std::to_string(header.numberof_segments());
    out += ",\"dynamicSymbols\":" + std::to_string(binary->dynamic_symbols().size());
    out += "}";
    return out;
}

static void appendSymbol(std::string& out, const Symbol& symbol, const Binary& binary) {
    if (out.back() != '[') out += ',';
    out += "{\"name\":" + jsonString(symbol.name());
    out += ",\"value\":" + jsonString(hex(symbol.value()));
    out += ",\"size\":" + std::to_string(symbol.size());
    out += ",\"type\":" + jsonString(LIEF::ELF::to_string(symbol.type()));
    out += ",\"binding\":" + jsonString(LIEF::ELF::to_string(symbol.binding()));
    out += ",\"visibility\":" + jsonString(LIEF::ELF::to_string(symbol.visibility()));
    out += ",\"defined\":" + std::string(symbol.shndx() != Symbol::UNDEF ? "true" : "false");
    if (const Section* section = symbol.section()) {
        out += ",\"section\":" + jsonString(section->name());
    }
    if (symbol.shndx() != Symbol::UNDEF) {
        const auto offset = binary.virtual_address_to_offset(symbol.value());
        if (offset) out += ",\"fileOffset\":" + jsonString(hex(offset.value()));
    }
    out += "}";
}

static std::string symbols(const std::string& path, std::string_view args) {
    uint64_t size = 0;
    if (!fileSize(path, &size)) {
        return "{\"path\":" + jsonString(path) + ",\"exists\":false}";
    }
    const auto binary = parseBinary(path);
    if (!binary) return commonPrefix(path, size) + ",\"elf\":false}";
    const size_t limit = jsonLimit(args);
    std::string out = commonPrefix(path, size) + ",\"count\":"
            + std::to_string(binary->dynamic_symbols().size()) + ",\"symbols\":[";
    size_t count = 0;
    for (const Symbol& symbol : binary->dynamic_symbols()) {
        if (count++ >= limit) break;
        appendSymbol(out, symbol, *binary);
    }
    out += "]";
    if (binary->dynamic_symbols().size() > limit) {
        out += ",\"truncated\":true,\"limit\":" + std::to_string(limit);
    }
    out += "}";
    return out;
}

static std::string relocations(const std::string& path, std::string_view args) {
    uint64_t size = 0;
    if (!fileSize(path, &size)) {
        return "{\"path\":" + jsonString(path) + ",\"exists\":false}";
    }
    const auto binary = parseBinary(path);
    if (!binary) return commonPrefix(path, size) + ",\"elf\":false}";
    const size_t limit = jsonLimit(args);
    std::string out = commonPrefix(path, size) + ",\"count\":"
            + std::to_string(binary->relocations().size()) + ",\"relocations\":[";
    size_t count = 0;
    for (const Relocation& relocation : binary->relocations()) {
        if (count++ >= limit) break;
        if (out.back() != '[') out += ',';
        out += "{\"address\":" + jsonString(hex(relocation.address()));
        out += ",\"type\":" + jsonString(LIEF::ELF::to_string(relocation.type()));
        out += ",\"typeValue\":" + std::to_string(Relocation::to_value(relocation.type()));
        out += ",\"addend\":" + std::to_string(relocation.addend());
        out += ",\"purpose\":" + std::to_string(static_cast<int>(relocation.purpose()));
        out += ",\"encoding\":" + std::to_string(static_cast<int>(relocation.encoding()));
        if (relocation.has_symbol() && relocation.symbol() != nullptr
                && !relocation.symbol()->name().empty()) {
            out += ",\"symbol\":" + jsonString(relocation.symbol()->name());
        }
        out += "}";
    }
    out += "]";
    if (binary->relocations().size() > limit) {
        out += ",\"truncated\":true,\"limit\":" + std::to_string(limit);
    }
    out += "}";
    return out;
}

static std::string strings(const std::string& path, std::string_view args) {
    uint64_t size = 0;
    if (!fileSize(path, &size)) {
        return "{\"path\":" + jsonString(path) + ",\"exists\":false}";
    }
    const auto binary = parseBinary(path);
    if (!binary) return commonPrefix(path, size) + ",\"elf\":false}";
    const size_t limit = jsonLimit(args);
    std::string out = commonPrefix(path, size) + ",\"strings\":[";
    size_t found = 0;
    bool truncated = false;
    for (const Section& section : binary->sections()) {
        if (section.type() != Section::TYPE::STRTAB) continue;
        const auto content = section.content();
        size_t begin = 0;
        for (size_t i = 0; i <= content.size(); ++i) {
            const bool end = i == content.size() || content[i] == 0;
            if (!end) continue;
            if (i - begin >= 4) {
                if (found >= limit) {
                    truncated = true;
                    break;
                }
                if (out.back() != '[') out += ',';
                out += "{\"section\":" + jsonString(section.name());
                out += ",\"offset\":" + jsonString(hex(section.offset() + begin));
                out += ",\"value\":" + jsonString(std::string_view(
                        reinterpret_cast<const char*>(content.data() + begin), i - begin));
                out += "}";
                ++found;
            }
            begin = i + 1;
        }
        if (truncated) break;
    }
    out += "]";
    if (truncated) out += ",\"truncated\":true,\"limit\":" + std::to_string(limit);
    out += "}";
    return out;
}

static std::string metadata(const std::string& path) {
    uint64_t size = 0;
    if (!fileSize(path, &size)) {
        return "{\"path\":" + jsonString(path) + ",\"exists\":false}";
    }
    const auto binary = parseBinary(path);
    if (!binary) return commonPrefix(path, size) + ",\"elf\":false}";
    const auto& header = binary->header();
    std::string out = commonPrefix(path, size);
    out += ",\"header\":{\"class\":"
            + jsonString(elfClassName(header.identity_class()));
    out += ",\"endianness\":" + jsonString(endianName(header.identity_data()));
    out += ",\"type\":" + jsonString(LIEF::ELF::to_string(header.file_type()));
    out += ",\"machine\":" + jsonString(machineName(header.machine_type()));
    out += ",\"entry\":" + jsonString(hex(header.entrypoint()));
    out += ",\"programHeaderOffset\":" + std::to_string(header.program_headers_offset());
    out += ",\"sectionHeaderOffset\":" + std::to_string(header.section_headers_offset());
    out += "}";
    out += ",\"interpreter\":" + jsonString(binary->interpreter());
    out += ",\"needed\":[";
    for (const DynamicEntry& entry : binary->dynamic_entries()) {
        if (const auto* library = entry.cast<DynamicEntryLibrary>()) {
            if (out.back() != '[') out += ',';
            out += jsonString(library->name());
        }
    }
    out += "],\"sections\":[";
    for (const Section& section : binary->sections()) {
        if (out.back() != '[') out += ',';
        out += "{\"name\":" + jsonString(section.name());
        out += ",\"type\":" + jsonString(LIEF::ELF::to_string(section.type()));
        out += ",\"offset\":" + std::to_string(section.offset());
        out += ",\"size\":" + std::to_string(section.size());
        out += ",\"virtualAddress\":" + jsonString(hex(section.virtual_address()));
        out += "}";
    }
    out += "],\"segments\":[";
    for (const Segment& segment : binary->segments()) {
        if (out.back() != '[') out += ',';
        out += "{\"type\":" + jsonString(LIEF::ELF::to_string(segment.type()));
        out += ",\"offset\":" + std::to_string(segment.file_offset());
        out += ",\"virtualAddress\":" + jsonString(hex(segment.virtual_address()));
        out += ",\"fileSize\":" + std::to_string(segment.physical_size());
        out += ",\"memorySize\":" + std::to_string(segment.virtual_size());
        out += ",\"flags\":" + std::to_string(static_cast<int>(segment.flags()));
        out += "}";
    }
    out += "]}";
    return out;
}

static std::string resolve(const std::string& path, std::string_view args) {
    const std::string wanted = jsonArgument(args, "name");
    if (wanted.empty()) return "{\"error\":\"resolve requires string argument 'name'\"}";
    const auto binary = parseBinary(path);
    if (!binary) {
        return "{\"path\":" + jsonString(path)
                + ",\"error\":\"LIEF could not parse this file\"}";
    }
    const Symbol* symbol = binary->get_dynamic_symbol(wanted);
    if (symbol == nullptr) symbol = binary->get_symtab_symbol(wanted);
    if (symbol == nullptr) {
        return "{\"path\":" + jsonString(path) + ",\"name\":"
                + jsonString(wanted) + ",\"found\":false}";
    }
    std::string out = "{\"path\":" + jsonString(path) + ",\"name\":"
            + jsonString(wanted) + ",\"found\":true";
    out += ",\"value\":" + jsonString(hex(symbol->value()));
    out += ",\"size\":" + std::to_string(symbol->size());
    if (symbol->shndx() != Symbol::UNDEF) {
        const auto offset = binary->virtual_address_to_offset(symbol->value());
        if (offset) out += ",\"fileOffset\":" + jsonString(hex(offset.value()));
    }
    out += ",\"type\":" + jsonString(LIEF::ELF::to_string(symbol->type()));
    out += ",\"binding\":" + jsonString(LIEF::ELF::to_string(symbol->binding()));
    out += ",\"defined\":" + std::string(
            symbol->shndx() != Symbol::UNDEF ? "true" : "false");
    out += "}";
    return out;
}

static std::string analyze(const std::string& op, const std::string& path,
                           std::string_view args) {
    try {
        if (op == "overview") return overview(path);
        if (op == "symbols") return symbols(path, args);
        if (op == "relocations") return relocations(path, args);
        if (op == "strings") return strings(path, args);
        if (op == "metadata") return metadata(path);
        if (op == "resolve") return resolve(path, args);
        return "{\"error\":\"unknown op '" + jsonEscape(op)
                + "'. Known ops: overview, symbols, relocations, strings, metadata, resolve\"}";
    } catch (const std::exception& error) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "analysis failed: %s", error.what());
        return "{\"error\":\"analysis failed: " + jsonEscape(error.what()) + "\"}";
    } catch (...) {
        return "{\"error\":\"analysis failed with an unknown native error\"}";
    }
}

static std::string disassemble(const std::string& path, const std::string& name,
        const std::string& address, int byteLimit, int count, const std::string& mode) {
    const auto binary = parseBinary(path);
    if (!binary) throw std::runtime_error("LIEF could not parse this file");
    uint64_t start = 0;
    uint64_t symbolSize = 0;
    if (!name.empty()) {
        const Symbol* symbol = binary->get_dynamic_symbol(name);
        if (!symbol) symbol = binary->get_symtab_symbol(name);
        if (!symbol || symbol->shndx() == Symbol::UNDEF)
            throw std::runtime_error("symbol is missing or undefined");
        if (symbol->type() != Symbol::TYPE::FUNC && symbol->type() != Symbol::TYPE::GNU_IFUNC)
            throw std::runtime_error("symbol is not a function");
        start = symbol->value();
        symbolSize = symbol->size();
    } else {
        size_t consumed = 0;
        start = std::stoull(address, &consumed, 16);
        if (consumed != address.size()) throw std::runtime_error("invalid hex address");
    }
    cs_arch arch;
    cs_mode csMode = CS_MODE_LITTLE_ENDIAN;
    std::string selectedMode = "default";
    const auto machine = binary->header().machine_type();
    if (binary->header().identity_data() != LIEF::ELF::Header::ELF_DATA::LSB)
        throw std::runtime_error("disasm currently supports little-endian ELF only");
    switch (machine) {
        case LIEF::ELF::ARCH::AARCH64: arch = CS_ARCH_AARCH64; break;
        case LIEF::ELF::ARCH::ARM:
            arch = CS_ARCH_ARM;
            selectedMode = mode == "auto" ? ((start & 1) ? "thumb" : "arm") : mode;
            csMode = selectedMode == "thumb" ? CS_MODE_THUMB : CS_MODE_ARM;
            start &= ~uint64_t{1};
            break;
        case LIEF::ELF::ARCH::X86_64: arch = CS_ARCH_X86; csMode = CS_MODE_64; break;
        case LIEF::ELF::ARCH::I386: arch = CS_ARCH_X86; csMode = CS_MODE_32; break;
        default: throw std::runtime_error("unsupported disasm architecture");
    }
    if (machine != LIEF::ELF::ARCH::ARM && mode != "auto")
        throw std::runtime_error("mode is only applicable to ARM32");
    if ((machine == LIEF::ELF::ARCH::AARCH64 && start % 4 != 0)
            || (machine == LIEF::ELF::ARCH::ARM && start % (selectedMode == "thumb" ? 2 : 4) != 0))
        throw std::runtime_error("instruction address is not aligned");
    const Segment* code = nullptr;
    for (const Segment& segment : binary->segments()) {
        if (segment.type() == Segment::TYPE::LOAD && segment.has(Segment::FLAGS::X)
                && start >= segment.virtual_address()
                && start - segment.virtual_address() < segment.content().size()) {
            code = &segment;
            break;
        }
    }
    if (!code) throw std::runtime_error("address is not in file-backed executable PT_LOAD content");
    const auto content = code->content();
    const size_t delta = start - code->virtual_address();
    size_t window = byteLimit > 0 ? byteLimit : (symbolSize > 0 ? std::min<uint64_t>(symbolSize, 4096) : 256);
    window = std::min(window, content.size() - delta);
    if (symbolSize > 0) window = std::min<uint64_t>(window, symbolSize);
    csh handle;
    if (cs_open(arch, csMode, &handle) != CS_ERR_OK)
        throw std::runtime_error("Capstone could not initialize");
    struct Close { csh& h; ~Close() { cs_close(&h); } } close{handle};
    cs_insn* instructions = nullptr;
    const size_t decoded = cs_disasm(handle, content.data() + delta, window, start, count, &instructions);
    struct Free { cs_insn* p; size_t n; ~Free() { cs_free(p, n); } } release{instructions, decoded};
    if (decoded == 0) throw std::runtime_error("no instruction decoded at this address");
    std::string out = "{\"backend\":\"capstone\",\"op\":\"disasm\",\"path\":" + jsonString(path)
            + ",\"machine\":" + jsonString(machineName(machine)) + ",\"mode\":" + jsonString(selectedMode)
            + ",\"addressKind\":\"elf_virtual_address\",\"address\":" + jsonString(hex(start))
            + ",\"fileOffset\":" + jsonString(hex(code->file_offset() + delta))
            + ",\"windowBytes\":" + std::to_string(window) + ",\"instructions\":[";
    size_t consumed = 0;
    for (size_t i = 0; i < decoded; ++i) {
        const auto& insn = instructions[i];
        if (i) out += ',';
        std::string bytes;
        for (size_t j = 0; j < insn.size; ++j) {
            char b[3]; snprintf(b, sizeof b, "%02x", insn.bytes[j]); bytes += b;
        }
        out += "{\"address\":" + jsonString(hex(insn.address))
                + ",\"fileOffset\":" + jsonString(hex(code->file_offset() + delta + consumed))
                + ",\"size\":" + std::to_string(insn.size) + ",\"bytes\":" + jsonString(bytes)
                + ",\"mnemonic\":" + jsonString(insn.mnemonic) + ",\"operands\":" + jsonString(insn.op_str) + "}";
        consumed += insn.size;
    }
    out += "],\"count\":" + std::to_string(decoded) + ",\"decodedBytes\":" + std::to_string(consumed)
            + ",\"nextAddress\":" + jsonString(hex(start + consumed))
            + ",\"stopReason\":" + jsonString(consumed == window ? "window_end" :
                (decoded == static_cast<size_t>(count) ? "instruction_limit" : "undecodable_bytes")) + "}";
    return out;
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_dev_deploy_core_DeployCore_nativeAnalyze(JNIEnv* env, jclass,
        jstring op, jstring path, jstring argsJson) {
    const char* cOp = op == nullptr ? nullptr : env->GetStringUTFChars(op, nullptr);
    const char* cPath = path == nullptr ? nullptr : env->GetStringUTFChars(path, nullptr);
    const char* cArgs = argsJson == nullptr ? nullptr : env->GetStringUTFChars(argsJson, nullptr);
    const std::string result = analyze(cOp == nullptr ? "" : cOp,
                                       cPath == nullptr ? "" : cPath,
                                       cArgs == nullptr ? "{}" : cArgs);
    if (cOp != nullptr) env->ReleaseStringUTFChars(op, cOp);
    if (cPath != nullptr) env->ReleaseStringUTFChars(path, cPath);
    if (cArgs != nullptr) env->ReleaseStringUTFChars(argsJson, cArgs);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_deploy_core_DeployCore_nativeDisassemble(JNIEnv* env, jclass,
        jstring path, jstring name, jstring address, jint bytes, jint count, jstring mode) {
    auto string = [env](jstring s) {
        const char* p = env->GetStringUTFChars(s, nullptr);
        std::string value = p == nullptr ? "" : p;
        if (p) env->ReleaseStringUTFChars(s, p);
        return value;
    };
    std::string result;
    try {
        result = disassemble(string(path), string(name), string(address), bytes, count, string(mode));
    } catch (const std::exception& e) {
        result = "{\"error\":" + jsonString(e.what()) + "}";
    } catch (...) {
        result = "{\"error\":\"unknown native disassembly failure\"}";
    }
    return env->NewStringUTF(result.c_str());
}
