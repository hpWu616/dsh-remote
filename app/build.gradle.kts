plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.dsh_apk"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.dsh_apk"
        minSdk = 24
        targetSdk = 37
        versionCode = 18
        versionName = "2.7"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}
