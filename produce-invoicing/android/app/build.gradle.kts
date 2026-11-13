import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/** Office API base URL for a flavor; empty means "not configured" (never a guessed host). */
fun apiBaseUrl(flavor: String): String =
    providers.gradleProperty("pi.apiBaseUrl.$flavor").orNull?.trim().orEmpty()

fun String.asBuildConfigString(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "bb.produce.invoicing"
    compileSdk = 37

    defaultConfig {
        applicationId = "bb.produce.invoicing"
        minSdk = 26 // D02 provisional default; confirm against the real device fleet.
        targetSdk = 36 // 37 opt-in behaviour changes deferred to Task 09 device QA
        versionCode = 1
        versionName = "0.0.1-task00"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "environment"
    productFlavors {
        // Developer workstation / emulator. Cleartext http permitted ONLY in this flavor.
        create("dev") {
            dimension = "environment"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            buildConfigField("String", "API_BASE_URL", apiBaseUrl("dev").asBuildConfigString())
            buildConfigField("boolean", "ALLOW_CLEARTEXT", "true")
            resValue("string", "app_name", "Produce Invoicing (dev)")
        }
        // Office-LAN pilot. HTTPS only; URL supplied at build time (and, from Task 04, provisioning).
        create("pilot") {
            dimension = "environment"
            buildConfigField("String", "API_BASE_URL", apiBaseUrl("pilot").asBuildConfigString())
            buildConfigField("boolean", "ALLOW_CLEARTEXT", "false")
            resValue("string", "app_name", "Produce Invoicing")
        }
    }

    buildTypes {
        release {
            // Pilot distribution is debug-only (docs/06). Release signing is a separate process.
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation("bb.produce.invoicing:domain")
    implementation(project(":core:data"))
    implementation(project(":core:printing"))
    implementation(project(":core:sync"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}
