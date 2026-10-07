plugins {
    id("com.android.application")
}

android {
    namespace = "dev.posedmcp"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "dev.posedmcp"
        minSdk = 31
        targetSdk = 36
        versionCode = 5
        versionName = "1.0.4"

        // Only the ABI the module is used on. The library is small, but the APK
        // is loaded into every scoped process, so there is no reason to carry
        // three copies of it.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    androidResources {
        // Generates res/xml/locales_config.xml from the resource folders that
        // exist and points the manifest at it, which is what puts this app in
        // Settings - Apps - 奈何桥 - Language on Android 13 and later. The locale
        // it names as the app's own comes from res/resources.properties.
        //
        // Whether a Chinese ROM still shows that entry is a separate question -
        // the picker is the platform's, and the per-app override is only honoured
        // where the system implements it. The translations themselves do not
        // depend on it: with values-zh-rCN present the app already follows the
        // system language on every release.
        generateLocaleConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    dependenciesInfo {
        includeInApk = false
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.kotlin_module",
                "META-INF/*.version",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
        }
    }
}

dependencies {
    // Provided by LSPosed at runtime; never packaged into the APK.
    compileOnly("de.robv.android.xposed:api:82")

    // And the framework that replaces it. Vector (LSPosed's rewrite, same author)
    // is natively a libxposed API 102 framework: it still loads classic modules,
    // but through a compatibility bridge that is demonstrably thinner than the
    // real thing - AndroidAppHelper.currentApplication() returns null there,
    // which is what made lua_exec's app.context() nil. Declaring both lets each
    // framework load the entry it is actually built for, instead of asking one
    // of them to emulate the other.
    compileOnly("io.github.libxposed:api:102.0.0")

    // Material 3 for the app's own screen. AppCompat comes with it: a Material3
    // theme is an AppCompat theme, so the activity has to be an
    // AppCompatActivity for the widgets to pick up their styling. Both bring
    // androidx.annotation in transitively, which is why it is no longer declared
    // on its own - pinning it here fought the version the runtime resolves.
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")

    // On-device smali disassembly and assembly. Pure Java, so it runs on ART -
    // apktool would not, its resource decoding shells out to a host-native aapt2.
    // This is also what lets the agent author injected code without a PC
    // toolchain: write smali, assemble to DEX here, hand it to plugin_load.
    implementation("org.smali:baksmali:2.5.2")
    implementation("org.smali:smali:2.5.2")

    // A Lua interpreter, for injected logic that would otherwise have to be
    // hand-written smali. Pure Java, so it dexes like anything else and rides
    // into every scoped process on the module's own class loader - there is no
    // compile step and no DEX to push for a script.
    implementation("org.luaj:luaj-jse:3.0.1")
}
