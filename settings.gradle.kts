pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // The classic Xposed API jar, still published by the Xposed project.
        // LSPosed ships these classes too (XposedBridge$LegacyApiSupport), so
        // this is the API a module actually talks to at runtime.
        maven {
            url = uri("https://api.xposed.info/")
            content {
                includeGroup("de.robv.android.xposed")
            }
        }
    }
}

rootProject.name = "posedmcp"
include(":app")
// Local source snapshot of the separately maintained Deploy contract.
include(":deploy-api")
