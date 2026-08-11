plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.azhilin.rokid.displaybridge.companion"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.azhilin.rokid.displaybridge.companion"
        minSdk = 28
        targetSdk = 36
        versionCode = 6
        versionName = "0.3.0"
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":protocol:kotlin"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.recyclerview)
    implementation(libs.material)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}

apply(from = rootProject.file("gradle/release-signing.gradle"))
