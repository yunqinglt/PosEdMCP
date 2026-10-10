plugins {
    id("com.android.application")
}

android {
    namespace = "dev.deploy"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "dev.deploy"
        minSdk = 31
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"

        // Only the ABI these devices use; LIEF will be statically linked in
        // here, so there is no reason to carry other architectures.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        abortOnError = false
    }
}

dependencies {
    // The AIDL contract Deploy implements and PosEdMCP binds to.
    implementation(project(":deploy-api"))
}
