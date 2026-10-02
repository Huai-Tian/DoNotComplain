plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dont.complain"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "dont.complain"
        minSdk = 27
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
}