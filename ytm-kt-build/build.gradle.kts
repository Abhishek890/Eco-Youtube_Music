import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlinx.serialization)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    jvmToolchain(17)

    sourceSets {
        val commonMain by getting {
            kotlin.srcDir("../ytm-kt-local/library/src/commonMain/kotlin")
            dependencies {
                api(libs.kmpresources)
                implementation(libs.composekit.kutil)
                implementation(libs.coroutines.core)
                implementation(libs.serialization.json)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
            }
        }

        val allJvmMain by creating {
            dependsOn(commonMain)
            kotlin.srcDir("../ytm-kt-local/library/src/allJvmMain/kotlin")
            dependencies {
                implementation(libs.ktor.client.cio)
                implementation(libs.newpipe)
            }
        }

        val androidMain by getting {
            dependsOn(allJvmMain)
        }
        val jvmMain by getting {
            dependsOn(allJvmMain)
        }
    }
}

android {
    namespace = "dev.toastbits.ytmkt"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
