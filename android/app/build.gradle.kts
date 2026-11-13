import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val tguDebugKeystorePath = System.getenv("TGU_DEBUG_KEYSTORE_PATH")
val tguDebugKeystorePassword = System.getenv("TGU_DEBUG_KEYSTORE_PASSWORD")
val hasTguCiSigning = !tguDebugKeystorePath.isNullOrBlank() && !tguDebugKeystorePassword.isNullOrBlank()
val tguCommunityApiBaseUrl = providers.gradleProperty("TGU_COMMUNITY_API_BASE_URL").orElse("").get().trim().trimEnd('/')

android {
    namespace = "com.rnmd.tgu"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rnmd.tgu"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        minSdk = 26
        targetSdk = 36
        versionCode = 25
        versionName = "0.9.6"
        buildConfigField("String", "TGU_COMMUNITY_API_BASE_URL", "\"$tguCommunityApiBaseUrl\"")
    }

    signingConfigs {
        if (hasTguCiSigning) {
            create("tguCiDebug") {
                storeFile = file(tguDebugKeystorePath!!)
                storePassword = tguDebugKeystorePassword
                keyAlias = "tgu-debug"
                keyPassword = tguDebugKeystorePassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (hasTguCiSigning) {
                signingConfig = signingConfigs.getByName("tguCiDebug")
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget("17")
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.github.jan-tennert.supabase:auth-kt:3.1.0")
    implementation("io.ktor:ktor-client-android:3.0.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    androidTestImplementation(platform("androidx.compose:compose-bom:2026.06.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.6.1")
}
