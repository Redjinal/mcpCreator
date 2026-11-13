import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Local durable storage boundary (Room). Entities, DAOs, migrations and the
// transactional issue operation arrive in Task 02. Only depends on :domain.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "bb.produce.invoicing.core.data"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    api("bb.produce.invoicing:domain")
}
