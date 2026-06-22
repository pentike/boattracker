plugins {
    id("com.android.application")
}

android {
    namespace = "hu.fenyveskupa.boattracker"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "hu.fenyveskupa.boattracker"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}
