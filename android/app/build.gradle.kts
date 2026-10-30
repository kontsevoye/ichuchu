import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.ksp)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.generateKotlin", "false")
}

android {
    namespace = "io.github.kontsevoye.ichuchu"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "io.github.kontsevoye.ichuchu"
        minSdk = 24
        targetSdk = 36

        val checkoutRoot = rootProject.projectDir.parentFile
        val resolvedVersion = Properties().apply {
            val output = providers.exec {
                workingDir(checkoutRoot)
                commandLine("node", "build-support/version.mjs", "--gradle")
            }.standardOutput.asText.get()
            load(output.reader())
        }
        versionCode = resolvedVersion.getProperty("versionCode").toInt()
        versionName = resolvedVersion.getProperty("versionName")

        System.getenv("ANDROID_ABI_FILTERS")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?.let { filters ->
                ndk {
                    abiFilters += filters
                }
            }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }

    signingConfigs {
        create("release") {
            val keystoreFile = System.getenv("KEYSTORE_FILE")?.let { file(it) } ?: file("key.jks")
            val keystorePassword = System.getenv("KEYSTORE_PASSWORD")
            val keyAliasEnv = System.getenv("KEY_ALIAS")
            val keyPasswordEnv = System.getenv("KEY_PASSWORD")

            if (keystorePassword != null && keyAliasEnv != null && keyPasswordEnv != null && keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = keystorePassword
                keyAlias = keyAliasEnv
                keyPassword = keyPasswordEnv
            }
        }
    }

    defaultConfig {
        buildConfigField("String", "UPDATE_REPOSITORY", "\"kontsevoye/ichuchu\"")
        buildConfigField("String", "UPDATE_DISTRIBUTION", "\"github\"")
        buildConfigField("boolean", "SELF_UPDATE_ENABLED", "false")
    }

    buildTypes {
        release {
            buildConfigField("boolean", "SELF_UPDATE_ENABLED", providers.gradleProperty("enableSelfUpdate").orElse("false").get())
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    buildTypes.create("updateTest") {
        initWith(buildTypes.getByName("debug"))
        applicationIdSuffix = ".updatetest"
        matchingFallbacks += "debug"
        buildConfigField("boolean", "SELF_UPDATE_ENABLED", "true")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            // Robolectric needs the merged resources to resolve app resources in tests.
            isIncludeAndroidResources = true
            all { test ->
                test.maxParallelForks = providers.gradleProperty("testForks").orElse("1").get().toInt().also {
                    require(it in 1..4) { "testForks must be between 1 and 4" }
                }
                // Roborazzi gates PNG capture on this system property; the Gradle
                // plugin usually wires it, but we pass it through manually so
                // `-Proborazzi.test.record=true` works with plain test tasks.
                test.systemProperty(
                    "roborazzi.test.record",
                    providers.gradleProperty("roborazzi.test.record").orElse("false").get(),
                )
            }
        }
    }
}

dependencies {
    implementation(libs.okhttp)
    implementation(libs.work.runtime)
    testImplementation(libs.mockwebserver)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation(libs.work.testing)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.sqlcipher)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.biometric:biometric:1.2.0-alpha05")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    implementation(libs.androidx.compose.foundation)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
