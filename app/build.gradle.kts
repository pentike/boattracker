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
        versionCode = 10
        versionName = "2.6"
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}
