plugins {
    id("com.android.application") version "8.13.2"
}

// One version for every platform: the one in the Maven build.
val appVersion = Regex("<artifactId>pingplanet</artifactId>\\s*<version>(\\d+)\\.(\\d+)\\.(\\d+)</version>")
    .find(file("../pom.xml").readText())?.groupValues ?: error("no x.y.z version in pom.xml")

// The test code is the desktop app's; only Platform.java and the screens are Android's own.
val sharedSources = tasks.register<Sync>("sharedSources") {
    from("../src/main/java")
    include(
        listOf(
            "Advice", "Engine", "Fetcher", "HttpPing", "InternetTest", "Lan", "Latency", "Markdown", "Net", "Overall",
            "Progress", "RecordingView", "Report", "Server", "Term", "Throughput", "View", "ViewAdapter", "WorldMap",
        ).map { "pingplanet/$it.java" }
    )
    into(layout.buildDirectory.dir("shared/java"))
}

android {
    namespace = "pingplanet.app"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "io.github.boaglio.pingplanet"
        minSdk = 26
        targetSdk = 36
        versionName = "${appVersion[1]}.${appVersion[2]}.${appVersion[3]}"
        versionCode = appVersion[1].toInt() * 10000 + appVersion[2].toInt() * 100 + appVersion[3].toInt()
    }

    // Set these to sign releases with your own key. Without them the release uses the debug key: it installs,
    // but a phone refuses to update it from a build signed on another machine.
    val keystore = System.getenv("ANDROID_KEYSTORE_FILE")
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true // Java 9+ library calls in the shared code, on older phones
    }

    sourceSets["main"].apply {
        java.srcDir(layout.buildDirectory.dir("shared/java"))
        resources.srcDir("../src/main/resources")
        resources.include("pingplanet/servers.csv", "pingplanet/world.txt")
    }

    lint {
        abortOnError = true
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}

tasks.named("preBuild") {
    dependsOn(sharedSources)
}
