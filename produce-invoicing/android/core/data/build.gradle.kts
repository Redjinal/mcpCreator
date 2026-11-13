import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Local durable storage boundary (Room). Depends only on :domain.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "bb.produce.invoicing.core.data"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    // Exported Room schemas are test assets so MigrationTestHelper can rebuild old versions.
    sourceSets.getByName("test").assets.srcDir("$projectDir/schemas")
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    api("bb.produce.invoicing:domain")
    api(libs.room.runtime) // AppDatabase (public) extends RoomDatabase
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.kotlinx.coroutines.core)

    // Robolectric runs Room against a real (host-native) SQLite on the JVM. On-device
    // instrumentation of the same scenarios is BLOCKED until a phone/emulator is available.
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
}
