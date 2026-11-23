plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.whatsapp.weirdoaudio"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        aidl = true
    }
}
