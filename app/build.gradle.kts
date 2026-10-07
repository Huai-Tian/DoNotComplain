plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.huai_tian.donotcomplain"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.huai_tian.donotcomplain"
        minSdk = 27
        targetSdk = 37
        versionCode = 2
        versionName = "1.1"
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
