plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.azhilin.rokid.displaybridge.protocol"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation(libs.junit)
}
