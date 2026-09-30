plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Optional local-only input. CI and ordinary source builds contain no accessory identity.
val localAuthenticationAssets = providers.environmentVariable("DIPLAY_AUTH_ASSETS_DIR")
    .orNull?.let { file(it).canonicalFile }

android {
    namespace = "com.shilapi.xcertplay"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.shihab.diplay"
        minSdk = 26
        targetSdk = 36
        versionCode = 26
        versionName = "0.2.7"
    }

    localAuthenticationAssets?.let { sourceSets.getByName("main").assets.srcDir(it) }

    signingConfigs {
        create("release") {
            storeFile = file(
                providers.environmentVariable("RELEASE_KEYSTORE_FILE")
                    .orElse(providers.gradleProperty("RELEASE_KEYSTORE_FILE"))
                    .orNull ?: "${rootDir}/release.keystore"
            )
            storePassword = providers.environmentVariable("RELEASE_KEYSTORE_PASSWORD")
                .orElse(providers.gradleProperty("RELEASE_KEYSTORE_PASSWORD"))
                .orNull
            keyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS")
                .orElse(providers.gradleProperty("RELEASE_KEY_ALIAS"))
                .orNull
            keyPassword = providers.environmentVariable("RELEASE_KEY_PASSWORD")
                .orElse(providers.gradleProperty("RELEASE_KEY_PASSWORD"))
                .orNull
        }
    }

    buildTypes {
        debug {
            // Standard debug signing works out of the box
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(platform(libs.androidx.compose.bom))
    implementation(project(":common"))
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.app.projected)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// No implicit import. Only the two explicitly selected local runtime assets are allowed.
val credentialAssets = files(android.sourceSets.flatMap { source ->
    source.assets.directories.map { directory ->
        fileTree(directory) {
            include("**/offline-mfi/**", "**/*.pk8", "**/*.p7b", "**/*.key",
                "**/*.pem", "**/*.p12", "**/*.pfx", "**/*.jks", "**/*.keystore")
        }
    }
})
val rejectBundledCredentials by tasks.registering {
    group = "verification"
    description = "Reject unexpected credential files in APK assets."
    val filesToCheck = credentialAssets
    val allowed = localAuthenticationAssets?.let { dir ->
        listOf("identity.pk8", "certificate.p7b").map { dir.resolve("offline-mfi/$it").canonicalFile }.toSet()
    } ?: emptySet()
    inputs.files(filesToCheck)
    doLast {
        check(allowed.all { it.isFile }) { "Explicit local authentication assets are incomplete" }
        val unexpected = filesToCheck.files.filter { it.canonicalFile !in allowed }
        check(unexpected.isEmpty()) { "Unexpected credential files in APK assets" }
    }
}
tasks.named("preBuild") { dependsOn(rejectBundledCredentials) }

// Car-test packages must be standalone. Keep ordinary source/CI builds identity-free.
val verifyStandaloneAuthentication by tasks.registering {
    group = "verification"
    description = "Require the explicit runtime authentication input for a standalone car-test APK."
    val directory = localAuthenticationAssets
    doLast {
        check(directory != null) {
            "Standalone car builds require DIPLAY_AUTH_ASSETS_DIR; assembleDebug alone is source-only."
        }
        check(listOf("identity.pk8", "certificate.p7b").all {
            directory.resolve("offline-mfi/$it").let { file -> file.isFile && file.length() > 0 }
        }) { "Standalone CarPlay authentication files are missing or empty" }
    }
}
tasks.named("preBuild") { mustRunAfter(verifyStandaloneAuthentication) }
tasks.register("assembleStandaloneDebug") {
    group = "build"
    description = "Build a standalone car-test APK with explicitly provisioned authentication."
    dependsOn(verifyStandaloneAuthentication, "assembleDebug")
}

val verifyReleaseSigning by tasks.registering {
    group = "verification"
    description = "Verify release keystore and credentials exist before building a release APK."
    doLast {
        val config = android.signingConfigs.getByName("release")
        val keystore = config.storeFile
        check(keystore != null && keystore.isFile && keystore.length() > 0L) {
            "Release keystore file is missing: set RELEASE_KEYSTORE_FILE or place release.keystore at the repository root."
        }
        check(!config.storePassword.isNullOrBlank()) { "RELEASE_KEYSTORE_PASSWORD is not set." }
        check(!config.keyAlias.isNullOrBlank()) { "RELEASE_KEY_ALIAS is not set." }
        check(!config.keyPassword.isNullOrBlank()) { "RELEASE_KEY_PASSWORD is not set." }
    }
}
tasks.matching { it.name.startsWith("assembleRelease") || it.name.startsWith("bundleRelease") }.configureEach {
    dependsOn(verifyReleaseSigning)
}
