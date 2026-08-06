plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.azhilin.rokid.displaybridge.glasses"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.azhilin.rokid.displaybridge.glasses"
        minSdk = 28
        targetSdk = 28
        versionCode = 3
        versionName = "0.2.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    implementation(project(":protocol:kotlin"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}

apply(from = rootProject.file("gradle/release-signing.gradle"))
