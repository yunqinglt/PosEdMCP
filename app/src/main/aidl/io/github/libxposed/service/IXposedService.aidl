package io.github.libxposed.service;

// The two stable transactions used by this app, shared by service API 100..102.
// Explicit IDs and the descriptor follow the upstream libxposed service contract:
// https://github.com/libxposed/service/blob/master/interface/src/main/aidl/io/github/libxposed/service/IXposedService.aidl
// Keep this subset separate from the injected XposedInterface API.
interface IXposedService {
    int getApiVersion() = 1;
    void updateRemotePreferences(String group, in Bundle diff) = 21;
}
