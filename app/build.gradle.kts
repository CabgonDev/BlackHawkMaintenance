import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.cabgon.blackhawk"
    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        named("debug") {
            kotlin.directories.add("build/generated/ksp/debug/kotlin")
            kotlin.directories.add("build/generated/ksp/debug/java")
        }
        named("release") {
            kotlin.directories.add("build/generated/ksp/release/kotlin")
            kotlin.directories.add("build/generated/ksp/release/java")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            // version = "3.22.1"
        }
    }

    defaultConfig {
        applicationId = "com.cabgon.blackhawk"
        minSdk = 24
        targetSdk = 36

        //Control de version
        versionCode = 237
        versionName = "2.3.7 (Hawk)"

        vectorDrawables.useSupportLibrary = true

        ndk {
            //noinspection ChromeOsAbiSupport
            abiFilters += setOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf(
                    "-std=c++17",
                    "-O3",
                    "-DNDEBUG"
                )
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release"
                )
            }
        }
    }

    signingConfigs {
        create("release") {
            val storeFilePath = keystoreProps.getProperty("storeFile") ?: ""
            if (storeFilePath.isNotBlank()) {
                storeFile = file(storeFilePath)
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            isShrinkResources = false

            // Evita binarios NDK lentos por jni debug
            isJniDebuggable = false

            // Debug: Chat IA habilitado para desarrollo local
            buildConfigField("boolean", "CHAT_AI_ENABLED", "true")
        }

        release {
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            isJniDebuggable = false

            // Release: Chat IA bloqueado (pantalla En Desarrollo)
            buildConfigField("boolean", "CHAT_AI_ENABLED", "true")

            // Firma release
            signingConfig = signingConfigs.getByName("release")

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        resValues = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/**",
            "okhttp3/**",
            "kotlin/**"
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.incremental", "true")
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.material)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.recyclerview)
    implementation(libs.coreKtx)
    implementation(libs.fragmentKtx)
    implementation(libs.lottie)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.activity)
    ksp(libs.room.compiler)

    implementation(libs.volley)

    implementation(libs.androidPdfViewer)
    implementation(libs.sqliteAndroid)
    implementation(libs.mlkitTranslate)
    implementation(libs.pdfboxAndroid)

    implementation(libs.retrofit)
    implementation(libs.retrofitMoshi)
    implementation(libs.okhttp)
    implementation(libs.moshiKotlin)
    ksp(libs.moshi.codegen)
    implementation(libs.jsoup)
    implementation(libs.retrofitScalars)

    implementation(libs.firebase.auth.ktx)
    implementation(libs.firebase.firestore.ktx)

    implementation(libs.firebase.storage.ktx)
    implementation(libs.firebase.remoteconfig.ktx)

    implementation(libs.swiperefreshlayout)
    implementation(libs.localbroadcastmanager)
}
