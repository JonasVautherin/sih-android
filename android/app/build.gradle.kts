import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

abstract class StageDirectory : Sync() {
    @get:OutputDirectory
    abstract val generatedDirectory: DirectoryProperty
}

val ardupilotBuild = providers.gradleProperty("sitlBuildDir")
    .map { rootProject.file(it) }
    .getOrElse(rootProject.file("../ardupilot/src/build/install"))
val px4Build = providers.gradleProperty("px4BuildDir")
    .map { rootProject.file(it) }
    .getOrElse(rootProject.file("../px4/src/build/install"))
val ardupilotLibsDir = layout.buildDirectory.dir("ardupilotLibs")
val px4LibsDir = layout.buildDirectory.dir("px4Libs")
val px4AssetsDir = layout.buildDirectory.dir("px4Assets")

val copyArdupilotLibraries by tasks.registering(StageDirectory::class) {
    val libraries = listOf("libarducopter.so", "libarduplane.so").map { ardupilotBuild.resolve("lib/$it") }
    doFirst {
        libraries.forEach {
            check(it.isFile) { "$it not found; run ../ardupilot/src/build.sh from android/ first" }
        }
    }
    generatedDirectory.set(ardupilotLibsDir)
    from(libraries) { into("arm64-v8a") }
    into(generatedDirectory)
}

val copyPx4Library by tasks.registering(StageDirectory::class) {
    val library = px4Build.resolve("lib/libpx4.so")
    doFirst {
        check(library.isFile) { "$library not found; run ../px4/src/build.sh from android/ first" }
    }
    generatedDirectory.set(px4LibsDir)
    from(library) { into("arm64-v8a") }
    into(generatedDirectory)
}

val copyPx4Assets by tasks.registering(StageDirectory::class) {
    doFirst {
        check(px4Build.resolve("share/px4/etc/init.d-posix/rcS").isFile &&
            px4Build.resolve("share/px4/bin/px4-alias.sh").isFile) {
            "PX4 startup assets not found in $px4Build; run ../px4/src/build.sh from android/ first"
        }
    }
    from(px4Build.resolve("share/px4/etc")) { into("px4/etc") }
    from(px4Build.resolve("share/px4/bin/px4-alias.sh")) { into("px4/bin") }
    generatedDirectory.set(px4AssetsDir)
    into(generatedDirectory)
}

val signingFlavor = providers.gradleProperty("signingFlavor").orNull
val signingPath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
if (signingPath != null) {
    require(signingFlavor in listOf("ardupilot", "px4")) {
        "Pass -PsigningFlavor=ardupilot or -PsigningFlavor=px4 to select which app owns ANDROID_KEYSTORE_PATH"
    }
}

android {
    namespace = "ch.vautherin.sih"
    compileSdk = 36
    ndkVersion = "29.0.13846066"

    buildFeatures {
        resValues = true
    }

    defaultConfig {
        minSdk = 28
        targetSdk = 36
        versionCode = providers.gradleProperty("versionCode").orElse("1").get().toInt()
        versionName = providers.gradleProperty("versionName").orElse("0.1").get()
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (signingPath != null) {
            create("production") {
                storeFile = file(signingPath)
                storeType = "JKS"
                storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").get()
            }
        }
    }

    flavorDimensions += "autopilot"
    productFlavors {
        create("ardupilot") {
            dimension = "autopilot"
            applicationId = "ch.vautherin.ap_sitl"
            resValue("string", "app_name", "ArduPilot SITL")
            externalNativeBuild {
                cmake {
                    arguments += "-DSIMULATOR=ardupilot"
                }
            }
        }
        create("px4") {
            dimension = "autopilot"
            applicationId = "ch.vautherin.px4_sih"
            resValue("string", "app_name", "PX4 SIH")
            externalNativeBuild {
                cmake {
                    arguments += listOf("-DSIMULATOR=px4", "-DPX4_LIBRARY=${px4Build.resolve("lib/libpx4.so")}")
                }
            }
        }
        if (signingPath != null) {
            getByName(signingFlavor!!).signingConfig = signingConfigs.getByName("production")
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    onVariants { variant ->
        val flavor = variant.productFlavors.single { it.first == "autopilot" }.second
        if (flavor == "ardupilot") {
            variant.sources.jniLibs?.addGeneratedSourceDirectory(copyArdupilotLibraries) { it.generatedDirectory }
        } else {
            variant.sources.jniLibs?.addGeneratedSourceDirectory(copyPx4Library) { it.generatedDirectory }
            variant.sources.assets?.addGeneratedSourceDirectory(copyPx4Assets) { it.generatedDirectory }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("io.mavsdk:mavsdk-kotlin-android:4.1.0")
    implementation("org.jetbrains.compose.foundation:foundation:1.11.1")
    implementation("org.jetbrains.compose.material3:material3:1.11.0-alpha07")
    implementation("org.jetbrains.compose.ui:ui:1.11.1")
    implementation("org.maplibre.compose:maplibre-compose:0.13.1")
    implementation("org.maplibre.spatialk:geojson:0.7.0")
}
