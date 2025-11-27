import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Helper function to generate the timestamp
fun getBuildTime(): String {
    val sdf = SimpleDateFormat("yyyy.MM.dd_HH.mm", Locale.US)
    return sdf.format(Date())
}

android {
    namespace = "com.accli.ecomrecorder"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.accli.ecomrecorder"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        // version name is now dynamic based on build time
        versionName = getBuildTime()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.video)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation(libs.androidx.camera.effects)
}