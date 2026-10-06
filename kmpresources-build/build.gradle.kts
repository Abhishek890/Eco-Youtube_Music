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
    compilerOptions {
        freeCompilerArgs.add("-XXLanguage:+ExpectActualClasses")
    }

    sourceSets {
        val commonMain by getting {
            kotlin.srcDir("../kmpresources-local/library/src/commonMain/kotlin")
            dependencies {
                implementation(libs.serialization.json)
            }
        }

        val notComposeMain by creating {
            dependsOn(commonMain)
            kotlin.srcDir("../kmpresources-local/library/src/notComposeMain/kotlin")
        }

        val allJvmMain by creating {
            dependsOn(notComposeMain)
            kotlin.srcDir("../kmpresources-local/library/src/allJvmMain/kotlin")
        }

        val androidMain by getting {
            dependsOn(allJvmMain)
            kotlin.srcDir("src/androidMain/kotlin")
        }

        val jvmMain by getting {
            dependsOn(allJvmMain)
            kotlin.srcDir("src/jvmMain/kotlin")
        }
    }
}

android {
    namespace = "sh.syk.kmpresources.library"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
