import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URI
import java.util.Properties

plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
    id("com.android.library")
    id("org.jetbrains.compose")
    id("com.google.devtools.ksp")
    id("androidx.room")
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.materialIconsExtended)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.datetime)
                implementation(libs.multiplatform.settings)
                implementation(libs.jnanoid)
                implementation(libs.gson)
                implementation(libs.google.api.client)
                implementation(libs.google.api.client.gson)
                implementation(libs.google.sheets)
                implementation(libs.google.gmail)
                implementation(libs.google.auth)
                implementation(libs.google.auth.credentials)
                implementation(libs.room.runtime)
                implementation(libs.room.ktx)
                implementation(libs.sqlite.bundled)
                implementation("androidx.sqlite:sqlite:2.5.0")
                implementation(libs.zxing.core)
                implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
                implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
                implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
                implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
                implementation("org.jetbrains.androidx.navigation:navigation-compose:2.8.0-alpha10")
                // GitLive Firebase — optional until google-services / FirebaseOptions are provisioned.
                // Class.forName in GitLiveFirestoreGateway still no-ops if App is not initialized.
                implementation(libs.gitlive.firebase.app)
                implementation(libs.gitlive.firebase.auth)
                implementation(libs.gitlive.firebase.firestore)
                implementation(libs.gitlive.firebase.storage)
                implementation(libs.kotlinx.serialization.json)
            }
        }
        val androidMain by getting {
            dependsOn(commonMain)
            dependencies {
                implementation(libs.kotlinx.coroutines.android)
                // compileOnly: local AARs cannot be implementation deps of a library module; :app packages them at runtime.
                compileOnly(files("${rootProject.projectDir}/app/libs/smartcardio-0.1.7.aar"))
                compileOnly(files("${rootProject.projectDir}/app/libs/acssmcio-0.6.2.aar"))
                implementation(libs.google.api.client.android)
        implementation("androidx.security:security-crypto:1.1.0-alpha06")
                implementation("androidx.biometric:biometric:1.1.0")
                implementation("androidx.activity:activity-compose:1.9.3")
                implementation("androidx.fragment:fragment-ktx:1.8.5")
                implementation("com.journeyapps:zxing-android-embedded:4.3.0")
                implementation("io.coil-kt:coil-compose:2.5.0")
                implementation("com.patrykandpatrick.vico:compose:2.0.0-alpha.20")
                implementation("com.patrykandpatrick.vico:compose-m3:2.0.0-alpha.20")
                implementation("androidx.browser:browser:1.8.0")
                // Gmail API on Android (UserRecoverableAuthException / GoogleAuthException) — not used for Firebase Sign-In.
                implementation("com.google.android.gms:play-services-auth:20.7.0")
                implementation("androidx.webkit:webkit:1.12.1")
                // GitLive Firestore on Android uses the native Firebase SDK (gRPC). Align all io.grpc
                // artifacts to 1.68.x — mixed versions cause NoClassDefFoundError / grpc-okhttp NPE on GoAway.
                implementation(libs.grpc.android)
                implementation(libs.grpc.okhttp)
                implementation(libs.grpc.api)
                implementation(libs.grpc.core)
                implementation(libs.grpc.context)
                implementation(libs.grpc.util)
                implementation(libs.grpc.protobuf.lite)
                implementation(libs.grpc.stub)
                // Mirror app POI setup — avoid duplicate stax-api / xmlbeans in merged dex
                implementation("org.apache.poi:poi:3.15") {
                    exclude(group = "org.apache.logging.log4j", module = "log4j-api")
                    exclude(group = "org.apache.xmlbeans", module = "xmlbeans")
                }
                implementation("org.apache.poi:poi-ooxml:3.15") {
                    exclude(group = "org.apache.logging.log4j", module = "log4j-api")
                    exclude(group = "org.apache.xmlbeans", module = "xmlbeans")
                    isTransitive = false
                }
                implementation("org.apache.poi:poi-ooxml-schemas:3.15") {
                    exclude(group = "org.apache.xmlbeans", module = "xmlbeans")
                }
                implementation("javax.xml.stream:stax-api:1.0-2")
                implementation("com.fasterxml.woodstox:woodstox-core:6.5.1") {
                    exclude(group = "javax.xml.stream", module = "stax-api")
                    exclude(group = "stax", module = "stax-api")
                }
                implementation("org.apache.xmlbeans:xmlbeans:2.5.0") {
                    exclude(group = "javax.xml.stream", module = "stax-api")
                    exclude(group = "stax", module = "stax-api")
                }
            }
        }
        val desktopMain by getting {
            dependsOn(commonMain)
            dependencies {
                implementation("com.github.librepdf:openpdf:1.3.30")
                implementation(compose.desktop.currentOs)
                implementation(files("${rootProject.projectDir}/shared/libs/smartcardio-api-0.1.7.jar"))
                implementation(libs.apdu4j.jnasmartcardio)
                implementation(libs.jna)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.sqlite.bundled)
                implementation(libs.webcam.capture)
                implementation(libs.webcam.capture.driver.native)
                implementation(libs.poi.ooxml)
                implementation(libs.zxing.javase)
                // Align gRPC with google-api-client (1.68.x). firebase-java-sdk pulls 1.52.1;
                // leaving grpc-core at 1.52.1 while grpc-api resolves to 1.68.2 causes
                // ClassNotFoundException: io.grpc.InternalGlobalInterceptors and hangs migration.
                implementation(libs.grpc.okhttp)
                implementation(libs.grpc.protobuf.lite)
                implementation(libs.grpc.stub)
                implementation(libs.grpc.api)
                implementation(libs.grpc.core)
                implementation(libs.grpc.context)
                implementation(libs.grpc.util)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}

// Read version from version.json — single source of truth for app + desktop + update checks
val versionFile = rootProject.file("version.json")
val versionProps = if (versionFile.exists()) {
    @Suppress("UNCHECKED_CAST")
    groovy.json.JsonSlurper().parse(versionFile) as Map<String, Any?>
} else emptyMap()
val versionName = versionProps["latestVersionName"] as? String ?: "1.0.0"
val versionCode = (versionProps["latestVersionCode"] as? Number)?.toInt() ?: 1
val updateManifestUrl =
    "https://raw.githubusercontent.com/TouristeAG/AdminList/main/version.json"
val updateFallbackStoreUrl =
    versionProps["storeUrl"] as? String
        ?: "https://play.google.com/store/apps/details?id=com.eventmanager.app"
val desktopUpdateFallbackUrl = "https://github.com/TouristeAG/NoctuList/releases"

val generatedAppBuildInfoDir = layout.buildDirectory.dir("generated/appBuildInfo/kotlin").get().asFile
val generatedAppBuildInfoFile = generatedAppBuildInfoDir.resolve("com/eventmanager/app/platform/AppBuildInfo.kt")
generatedAppBuildInfoDir.mkdirs()
generatedAppBuildInfoFile.parentFile.mkdirs()
generatedAppBuildInfoFile.writeText(
    """
    |package com.eventmanager.app.platform
    |
    |object AppBuildInfo {
    |    const val UPDATE_MANIFEST_URL = "$updateManifestUrl"
    |    const val UPDATE_FALLBACK_STORE_URL = "$updateFallbackStoreUrl"
    |    const val DESKTOP_UPDATE_FALLBACK_URL = "$desktopUpdateFallbackUrl"
    |    const val VERSION_NAME = "$versionName"
    |    const val VERSION_CODE = $versionCode
    |}
    """.trimMargin(),
)

kotlin.sourceSets.named("commonMain") {
    kotlin.srcDir(generatedAppBuildInfoDir)
}

configurations.matching {
    it.name.contains("android", ignoreCase = true)
}.configureEach {
    exclude(group = "commons-logging", module = "commons-logging")
    resolutionStrategy {
        force("org.apache.xmlbeans:xmlbeans:2.5.0")
        force("javax.xml.stream:stax-api:1.0-2")
        eachDependency {
            if (requested.group == "org.apache.xmlbeans" && requested.name == "xmlbeans") {
                useVersion("2.5.0")
            }
            if (requested.group == "javax.xml.stream" && requested.name == "stax-api") {
                useVersion("1.0-2")
            }
        }
    }
}

// Desktop + Android: keep all io.grpc artifacts on one version (Firestore native SDK / firebase-java-sdk).
configurations.matching {
    val n = it.name.lowercase()
    n.contains("desktop") || n.contains("jvm") || (n.contains("android") && !n.contains("test"))
}.configureEach {
    resolutionStrategy {
        force(
            "io.grpc:grpc-api:${libs.versions.grpc.get()}",
            "io.grpc:grpc-core:${libs.versions.grpc.get()}",
            "io.grpc:grpc-context:${libs.versions.grpc.get()}",
            "io.grpc:grpc-stub:${libs.versions.grpc.get()}",
            "io.grpc:grpc-okhttp:${libs.versions.grpc.get()}",
            "io.grpc:grpc-android:${libs.versions.grpc.get()}",
            "io.grpc:grpc-protobuf-lite:${libs.versions.grpc.get()}",
            "io.grpc:grpc-util:${libs.versions.grpc.get()}",
        )
    }
}

android {
    namespace = "com.eventmanager.app"
    compileSdk = 34
    defaultConfig {
        minSdk = 26
        buildConfigField("String", "VERSION_NAME", "\"$versionName\"")
        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"$updateManifestUrl\"")
        buildConfigField("String", "UPDATE_FALLBACK_STORE_URL", "\"$updateFallbackStoreUrl\"")
    }
    sourceSets["main"].res.srcDirs(
        "src/androidMain/res",
        "${rootProject.projectDir}/app/src/main/res"
    )
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    add("kspAndroid", libs.room.compiler)
    add("kspDesktop", libs.room.compiler)
}

room {
    schemaDirectory("$projectDir/schemas")
}

val desktopBiometricResourcesDir = layout.projectDirectory.dir("src/desktopMain/resources")

tasks.register("prepareDesktopBiometricNativeLibs") {
    val biometrikJar = layout.buildDirectory.file("biometrik-native/biometrik-jvm-1.0.2.jar")
    outputs.dir(desktopBiometricResourcesDir)

    doLast {
        desktopBiometricResourcesDir.asFile.mkdirs()
        val jar = biometrikJar.get().asFile
        if (!jar.exists()) {
            jar.parentFile.mkdirs()
            URI("https://repo1.maven.org/maven2/io/github/n7ghtm4r3/biometrik-jvm/1.0.2/biometrik-jvm-1.0.2.jar")
                .toURL()
                .openStream()
                .use { input -> jar.outputStream().use { output -> input.copyTo(output) } }
        }
        copy {
            from(zipTree(jar)) {
                include("LocalAuthenticationEngine.dylib", "WindowsHelloEngine.dll", "LinuxPolkitEngine.so")
            }
            into(desktopBiometricResourcesDir)
        }
        val macSource = layout.projectDirectory.file("nativeengines/macos/LocalAuthenticationEngine.m").asFile
        if (macSource.exists() && System.getProperty("os.name").startsWith("Mac OS")) {
            val dylib = desktopBiometricResourcesDir.file("LocalAuthenticationEngine.dylib").asFile
            project.exec {
                commandLine(
                    "clang",
                    "-dynamiclib",
                    "-framework", "LocalAuthentication",
                    "-framework", "Foundation",
                    "-o", dylib.absolutePath,
                    macSource.absolutePath
                )
            }
        }
    }
}

tasks.named("compileKotlinDesktop") {
    dependsOn("prepareDesktopBiometricNativeLibs")
}

tasks.matching { it.name == "desktopProcessResources" }.configureEach {
    dependsOn("prepareDesktopBiometricNativeLibs")
}

configurations.matching { it.name.contains("desktop", ignoreCase = true) }.configureEach {
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-android")
}

compose.resources {
    publicResClass = true
    packageOfResClass = "com.eventmanager.app.resources"
}

val syncWebFormResources = tasks.register<Copy>("syncWebFormResources") {
    from(rootProject.file("webform"))
    into(layout.projectDirectory.dir("src/commonMain/composeResources/files/webform"))
}

tasks.matching { it.name.contains("generateResourceAccessors", ignoreCase = true) }.configureEach {
    dependsOn(syncWebFormResources)
}
tasks.matching { it.name.contains("copyNonXmlValueResources", ignoreCase = true) }.configureEach {
    dependsOn(syncWebFormResources)
}

kotlin.sourceSets.all {
    languageSettings {
        optIn("androidx.compose.material3.ExperimentalMaterial3Api")
        optIn("androidx.compose.foundation.ExperimentalFoundationApi")
        optIn("androidx.compose.animation.ExperimentalAnimationApi")
    }
}
