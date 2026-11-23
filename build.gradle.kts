plugins {
    id("com.android.application") version "9.3.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
}

android {
    namespace = "io.shadereply"
    compileSdk {
        version = release(37) { minorApiLevel = 0 }
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "io.shadereply"
        minSdk = 37
        targetSdk = 37
        versionCode = 49
        versionName = "0.1.48"
    }
    providers.gradleProperty("shadereplyDebugKeystore").orNull?.let { path ->
        signingConfigs.getByName("debug").storeFile = file(path)
    }
    sourceSets.named("main") {
        manifest.srcFile("AndroidManifest.xml")
        res.setSrcDirs(listOf("res"))
        resources.setSrcDirs(listOf("resources"))
        assets.setSrcDirs(listOf("assets"))
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // LSPosed's module class loader resolves native libraries directly inside the APK.
    packaging.jniLibs.useLegacyPackaging = false
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
    implementation("org.luckypray:dexkit:2.2.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("io.github.suqi8.coui.kmp:coui-ui")
    implementation("io.github.suqi8.coui.kmp:coui-preference")
}
