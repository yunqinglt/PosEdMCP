#pragma once
#include <stddef.h>

/* Inputs are ASCII/IDNA names. A trailing root dot and case are insignificant. */
static int dns_rule_matches(const char *host, const char *rule) {
    if (!host || !rule) return 0;
    size_t hn = 0, rn = 0;
    while (host[hn]) { if (++hn > 254) return 0; }
    while (rule[rn]) { if (++rn > 255) return 0; }
    if (hn && host[hn - 1] == '.') --hn;
    if (rn && rule[rn - 1] == '.') --rn;
    int wildcard = rn > 2 && rule[0] == '*' && rule[1] == '.';
    if (wildcard) { rule += 2; rn -= 2; }
    if ((!wildcard && hn != rn) || (wildcard && (hn <= rn || host[hn-rn-1] != '.'))) return 0;
    const char *suffix = host + hn - rn;
    for (size_t i = 0; i < rn; ++i) {
        unsigned char c = (unsigned char)suffix[i], r = (unsigned char)rule[i];
        if (c >= 'A' && c <= 'Z') c += 'a' - 'A';
        if (r >= 'A' && r <= 'Z') r += 'a' - 'A';
        if (c != r) return 0;
    }
    return rn != 0;
}
