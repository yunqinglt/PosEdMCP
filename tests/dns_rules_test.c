#include <assert.h>
#include <stdio.h>
#include "../app/src/main/cpp/dns_rules.h"
int main(void) {
    assert(dns_rule_matches("ads.example.com", "ads.example.com"));
    assert(dns_rule_matches("ADS.Example.COM.", "ads.example.com"));
    assert(!dns_rule_matches("sub.ads.example.com", "ads.example.com"));
    assert(dns_rule_matches("sub.ads.example.com", "*.ads.example.com"));
    assert(!dns_rule_matches("ads.example.com", "*.ads.example.com"));
    assert(!dns_rule_matches("notads.example.com", "*.ads.example.com"));
    assert(!dns_rule_matches("ads.example.com.evil.org", "*.example.com"));
    assert(!dns_rule_matches("example.com", "*.example.com"));
    assert(!dns_rule_matches("ex.com", "*.example.com"));
    assert(!dns_rule_matches(NULL, "*.example.com"));
    assert(!dns_rule_matches("", "*.example.com"));
    assert(!dns_rule_matches("127.0.0.1", "*.example.com"));
    puts("PASS native DNS matcher (12 assertions)");
}
