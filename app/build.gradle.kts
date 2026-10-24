import com.google.protobuf.gradle.*
import java.net.URI
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.protobuf)
}

// Public deployment settings only. No bearer token, private key or provider credential enters the APK.
val clientConfig = Properties().apply {
    val configFile = rootProject.file(".local/phone-server-test/client.properties")
    if (configFile.isFile) configFile.inputStream().use { load(it) }
}
val configuredServer = providers.gradleProperty("ghostServerUrl")
    .orElse(providers.environmentVariable("GHOST_SERVER_URL"))
    .orElse(clientConfig.getProperty("server_url", "")).get().trim().trimEnd('/')
val configuredCloudKey = providers.gradleProperty("ghostCloudPublicKey")
    .orElse(providers.environmentVariable("GHOST_CLOUD_PUBLIC_KEY"))
    .orElse(clientConfig.getProperty("cloud_public_key") ?: rootProject.file(".local/phone-server-test/server-public-key.txt")
        .takeIf { it.isFile }?.readText()?.trim().orEmpty()).get()
if (configuredServer.isNotEmpty()) {
    val address = URI(configuredServer)
    require(address.scheme in listOf("http", "https") && !address.host.isNullOrBlank() &&
        address.rawUserInfo == null && address.rawQuery == null && address.rawFragment == null &&
        address.port in -1..65535 && address.port != 0 && configuredServer.length <= 512) {
        "ghostServerUrl must be a server address without credentials, query or fragment"
    }
}

android {
    namespace = "org.ghost"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.ghost"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-dev"
        val cloudKey = configuredCloudKey
        require(cloudKey.length <= 128 && cloudKey.matches(Regex("[A-Za-z0-9_-]*"))) {
            "ghostCloudPublicKey must be a base64url P-256 SPKI public key"
        }
        buildConfigField("String", "CLOUD_PUBLIC_KEY", "\"$cloudKey\"")
        buildConfigField("String", "SERVER_URL", "\"$configuredServer\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    sourceSets {
        getByName("main").proto { srcDir("$rootDir/contracts") }
        getByName("test").resources.directories.add("$rootDir/contracts/fixtures")
        getByName("test").resources.directories.add("$projectDir/schemas")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.base/java.util=ALL-UNNAMED")
            it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst {
        require(configuredServer.startsWith("https://") && configuredCloudKey.isNotEmpty()) {
            "Release builds require ghostServerUrl (HTTPS) and the trusted ghostCloudPublicKey"
        }
    }
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}" }
    generateProtoTasks {
        all().configureEach { builtins { maybeCreate("java").option("lite") } }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.nearby.connections)
    implementation(libs.protobuf.javalite)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

