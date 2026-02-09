import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.android.build.gradle.internal.api.BaseVariantOutputImpl

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

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
        versionName = getBuildTime()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
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

    applicationVariants.all {
        if (buildType.name == "release") {
            val version = this.versionName

            outputs.all {
                (this as? BaseVariantOutputImpl)?.outputFileName = "ECOM Recorder $version.apk"
            }
        }
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
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation(libs.androidx.camera.effects)
    implementation("com.google.android.gms:play-services-ads:24.9.0")
    implementation("com.google.guava:guava:32.1.3-android")
}
