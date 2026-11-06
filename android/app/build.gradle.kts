plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

/** Every push to main publishes a release, so the commit count orders the versions the in-app updater compares. */
val commitCount: Int = providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }
    .standardOutput.asText.get().trim().toInt()

/** The release key stays out of the repo: `glosa.*` in ~/.gradle/gradle.properties locally, `GLOSA_*` variables in CI. */
fun signingValue(property: String): String? =
    providers.gradleProperty(property).orNull ?: providers.environmentVariable(property.uppercase().replace('.', '_')).orNull

android {
    namespace = "com.maxi.glosa"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.maxi.glosa"
        minSdk = 31
        targetSdk = 36
        versionCode = commitCount
        versionName = "0.3.$commitCount"
    }

    signingConfigs {
        val keystore = signingValue("glosa.keystore")
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = signingValue("glosa.keystore.password")
                keyAlias = signingValue("glosa.key.alias") ?: "glosa"
                keyPassword = storePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without the key the release comes out unsigned (app-release-unsigned.apk), which anyone cloning can still build.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.readium.shared)
    implementation(libs.readium.streamer)
    implementation(libs.readium.navigator)
    implementation(libs.mlkit.genai.prompt)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver)
    // Android's own org.json is a stub in local unit tests.
    testImplementation(libs.org.json)
}
