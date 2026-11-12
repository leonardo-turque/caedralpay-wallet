import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val releaseSecretsFile = providers.environmentVariable("CAEDRAL_SIGNING_PROPERTIES").orNull?.let(::file)
val releaseSecrets = Properties().apply {
    if (releaseSecretsFile?.isFile == true) releaseSecretsFile.inputStream().use { load(it) }
}
val isolatedValidation = providers.gradleProperty("caedralIsolatedUiTest").orNull == "true"
val releaseValidation = providers.gradleProperty("caedralReleaseValidation").orNull == "true"
check(!releaseValidation || isolatedValidation) { "Testes release exigem o pacote isolado." }

android {
    testBuildType = if (releaseValidation) "release" else "debug"
    namespace = "com.caedralpay.wallet"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // Validação em pacote separado: nunca substituir a instalação pessoal.
        applicationId = if (isolatedValidation) {
            "com.caedralpay.wallet.validation"
        } else "com.caedralpay.wallet"
        minSdk = 37
        targetSdk = 37
        versionCode = providers.gradleProperty("caedralVersionCode").orNull?.toInt()?.also { require(it in 1..2_100_000_000) } ?: 2
        versionName = "0.1.0-beta.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSecrets.isNotEmpty()) create("betaRelease") {
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
            storeFile = file(releaseSecrets.getProperty("storeFile"))
            storePassword = releaseSecrets.getProperty("storePassword")
            keyAlias = releaseSecrets.getProperty("keyAlias")
            keyPassword = releaseSecrets.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isDebuggable = false
            if (releaseSecrets.isNotEmpty()) signingConfig = signingConfigs.getByName("betaRelease")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseValidation) proguardFiles("instrumentation-keep-rules.pro")
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

tasks.register("verifyReleaseSigning") {
    doLast {
        check(releaseSecrets.isNotEmpty()) { "Configure CAEDRAL_SIGNING_PROPERTIES com o arquivo privado de assinatura." }
        check(listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all { !releaseSecrets.getProperty(it).isNullOrBlank() })
        check(file(releaseSecrets.getProperty("storeFile")).isFile)
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn("verifyReleaseSigning") }

dependencies {
    if (releaseValidation) add("releaseImplementation", libs.androidx.compose.ui.test.manifest)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation("com.trustwallet:wallet-core:4.8.7")
}
