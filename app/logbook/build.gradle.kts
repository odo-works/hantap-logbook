import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
android {
    namespace = "io.odoworks.logbook"
    compileSdk = 35
    useLibrary("android.car")
    defaultConfig {
        applicationId = "io.odoworks.logbook"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "io.odoworks.logbook.ExportEvidenceInstrumentation"
        buildConfigField("long", "PAUSE_TIMEOUT", "600000L")
    }
    // release 서명: 키와 비밀번호는 레포 밖 파일에서 읽는다. 파일이 없으면 서명 없이 빌드된다.
    // 경로는 HANTAP_KEYSTORE_PROPS 환경 변수로 바꿀 수 있다(기본 ~/.config/odoworks/keystore.properties).
    val keystoreProps = File(
        System.getenv("HANTAP_KEYSTORE_PROPS") ?: "${System.getProperty("user.home")}/.config/odoworks/keystore.properties"
    )
    if (keystoreProps.isFile) {
        val p = Properties().apply { keystoreProps.inputStream().use { load(it) } }
        signingConfigs {
            create("release") {
                storeFile = file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        getByName("debug") { buildConfigField("long", "PAUSE_TIMEOUT", "60000L") }
        getByName("release") { if (keystoreProps.isFile) signingConfig = signingConfigs.getByName("release") }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation(platform("androidx.compose:compose-bom:2024.04.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    testImplementation("junit:junit:4.13.2")
}
