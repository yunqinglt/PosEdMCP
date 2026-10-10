plugins {
    id("com.android.application")
}

android {
    namespace = "dev.deploy"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.deploy"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
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
