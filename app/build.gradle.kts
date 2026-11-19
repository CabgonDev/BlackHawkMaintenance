import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
}

val signingPropertiesPath = providers.gradleProperty("releaseSigningPropertiesFile").orNull
    ?: providers.environmentVariable("RELEASE_SIGNING_PROPERTIES_FILE").orNull
    ?: "keystore.properties"
val keystorePropsFile = rootProject.file(signingPropertiesPath)
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

val releaseSigningPropertyNames = listOf(
    "storeFile",
    "storePassword",
    "keyAlias",
    "keyPassword"
)
val missingReleaseSigningProperties = releaseSigningPropertyNames.filter { name ->
    keystoreProps.getProperty(name)?.trim().isNullOrEmpty()
}
val releasePackagingRequested = gradle.startParameter.taskNames.any { taskName ->
    taskName.substringAfterLast(':').contains("release", ignoreCase = true) &&
        taskName.substringAfterLast(':').contains(
            Regex("assemble|bundle|package|install", RegexOption.IGNORE_CASE)
        )
}

if (releasePackagingRequested) {
    require(keystorePropsFile.isFile) {
        "Release signing properties are unavailable. Configure releaseSigningPropertiesFile or RELEASE_SIGNING_PROPERTIES_FILE."
    }
    require(missingReleaseSigningProperties.isEmpty()) {
        "Release signing configuration is incomplete. Missing properties: ${missingReleaseSigningProperties.joinToString()}"
    }
    require(file(keystoreProps.getProperty("storeFile").trim()).isFile) {
        "Release signing keystore file is missing or unreadable."
    }
}

android {
    namespace = "com.cabgon.blackhawk"
    compileSdk = 37

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

    defaultConfig {
        applicationId = "com.cabgon.blackhawk"
        minSdk = 28
        targetSdk = 37

        versionCode = 502
        versionName = "5.0.2 (Hawk)"

        vectorDrawables.useSupportLibrary = true

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
            isJniDebuggable = false
        }

        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            isJniDebuggable = false
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
    testImplementation(libs.junit)
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
    implementation(libs.biometric)
    ksp(libs.room.compiler)

    implementation(libs.volley)

    implementation(libs.androidxPdfViewerFragment)
    implementation(libs.sqliteAndroid)
    implementation(libs.mlkitTranslate)

    implementation(libs.retrofit)
    implementation(libs.retrofitMoshi)
    implementation(libs.okhttp)
    implementation(libs.moshiKotlin)
    ksp(libs.moshi.codegen)
    implementation(libs.jsoup)
    implementation(libs.retrofitScalars)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth.ktx)
    implementation(libs.firebase.firestore.ktx)
    implementation(libs.firebase.storage.ktx)
    implementation(libs.firebase.remoteconfig.ktx)
    implementation(libs.firebase.functions.ktx)

    implementation(libs.swiperefreshlayout)
    implementation(libs.localbroadcastmanager)

    implementation(libs.core.splashscreen)
    implementation(libs.lightcompressor)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
}
