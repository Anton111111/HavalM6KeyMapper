plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val platformPk8 = providers.gradleProperty("platformPk8")
    .orElse(providers.environmentVariable("HAVAL_PLATFORM_PK8"))
val defaultPlatformPk8 = rootProject.file("signing/platform.pk8")
val androidSdkRoot = providers.environmentVariable("ANDROID_SDK_ROOT")
    .orElse(providers.environmentVariable("ANDROID_HOME"))
val appVersionName = "1.1"

fun resolvePlatformPk8Path(): String {
    platformPk8.orNull?.let { return it }
    if (defaultPlatformPk8.isFile) return defaultPlatformPk8.absolutePath
    throw GradleException(
        "Set -PplatformPk8 or HAVAL_PLATFORM_PK8, or place the key at ${defaultPlatformPk8.absolutePath}",
    )
}

android {
    namespace = "com.anton111111.haval.m6.keymapper"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.anton111111.haval.m6.keymapper"
        minSdk = 28
        targetSdk = 28
        versionCode = 2
        versionName = appVersionName
    }

    buildFeatures {
        compose = true
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    lint {
        disable += "ExpiredTargetSdkVersion"
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.outputs.forEach { output ->
            val fileNameSuffix = if (variant.buildType == "release") "release-unsigned" else variant.buildType
            output.outputFileName.set("m6keymapper-$fileNameSuffix-$appVersionName.apk")
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}

tasks.register<Exec>("assemblePlatformDebug") {
    group = "build"
    description = "Build and sign the debug APK with an external platform PK8 key."
    dependsOn("assembleDebug")
    doFirst {
        val privateKey = resolvePlatformPk8Path()
        val sdkRoot = androidSdkRoot.orNull
            ?: throw GradleException("Set ANDROID_SDK_ROOT or ANDROID_HOME")
        val buildTools = file("$sdkRoot/build-tools").listFiles()
            ?.filter { it.isDirectory }
            ?.maxByOrNull { it.name.substringBefore('.').toIntOrNull() ?: 0 }
            ?: throw GradleException("No Android SDK build-tools found under $sdkRoot")
        val apksigner = file("${buildTools.absolutePath}/apksigner")
        if (!apksigner.isFile) throw GradleException("apksigner not found at $apksigner")
        val certificate = rootProject.file("signing/platform.x509.pem")
        if (!certificate.isFile) throw GradleException("Platform certificate not found: $certificate")
        val inputApk = layout.buildDirectory.file("outputs/apk/debug/m6keymapper-debug-$appVersionName.apk").get().asFile
        val outputApk = layout.buildDirectory.file("outputs/apk/debug/m6keymapper-debug-platform-$appVersionName.apk").get().asFile
        commandLine(
            apksigner.absolutePath,
            "sign",
            "--key", file(privateKey).absolutePath,
            "--cert", certificate.absolutePath,
            "--out", outputApk.absolutePath,
            inputApk.absolutePath,
        )
    }
}

tasks.register<Exec>("assemblePlatformRelease") {
    group = "build"
    description = "Build a minified release APK and sign it with an external platform PK8 key."
    dependsOn("assembleRelease")
    doFirst {
        val privateKey = resolvePlatformPk8Path()
        val sdkRoot = androidSdkRoot.orNull
            ?: throw GradleException("Set ANDROID_SDK_ROOT or ANDROID_HOME")
        val buildTools = file("$sdkRoot/build-tools").listFiles()
            ?.filter { it.isDirectory }
            ?.maxByOrNull { it.name.substringBefore('.').toIntOrNull() ?: 0 }
            ?: throw GradleException("No Android SDK build-tools found under $sdkRoot")
        val apksigner = file("${buildTools.absolutePath}/apksigner")
        if (!apksigner.isFile) throw GradleException("apksigner not found at $apksigner")
        val certificate = rootProject.file("signing/platform.x509.pem")
        if (!certificate.isFile) throw GradleException("Platform certificate not found: $certificate")
        val inputApk = layout.buildDirectory.file("outputs/apk/release/m6keymapper-release-unsigned-$appVersionName.apk").get().asFile
        val outputApk = layout.buildDirectory.file("outputs/apk/release/m6keymapper-release-platform-$appVersionName.apk").get().asFile
        commandLine(
            apksigner.absolutePath,
            "sign",
            "--key", file(privateKey).absolutePath,
            "--cert", certificate.absolutePath,
            "--out", outputApk.absolutePath,
            inputApk.absolutePath,
        )
    }
}
