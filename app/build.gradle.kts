import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val momentoApiBaseUrl = providers.gradleProperty("momentoApiBaseUrl")
    .orElse("")
    .get().trim().trimEnd('/')
val googleServerClientId = providers.gradleProperty("googleServerClientId")
    .orElse(providers.environmentVariable("GOOGLE_SERVER_CLIENT_ID")).getOrElse("")
val yandexClientId = providers.gradleProperty("yandexClientId")
    .orElse(providers.environmentVariable("YANDEX_CLIENT_ID")).getOrElse("")
val vkClientId = providers.gradleProperty("vkClientId")
    .orElse(providers.environmentVariable("VK_CLIENT_ID")).getOrElse("")
val vkClientSecret = providers.gradleProperty("vkClientSecret")
    .orElse(providers.environmentVariable("VK_CLIENT_SECRET")).getOrElse("")
// An auth-verification APK must never silently ship with disabled OTP controls.
// Fail closed for every distributable APK. Local offline UI builds require an explicit opt-in.
val allowOfflinePreview = providers.gradleProperty("momentoAllowOfflinePreview").orElse("false").get().toBooleanStrict()
val requireLiveAuth = providers.gradleProperty("momentoRequireLiveAuth").orElse("true").get().toBooleanStrict()
val apiUri = runCatching { URI(momentoApiBaseUrl) }.getOrNull()
val apiHost = apiUri?.host?.lowercase()?.removeSuffix(".")?.removeSurrounding("[", "]")
val apiConfigured = apiUri != null && apiUri.scheme == "https" && apiHost != null &&
    apiUri.rawUserInfo == null && apiUri.rawQuery == null && apiUri.rawFragment == null &&
    (apiUri.port == -1 || apiUri.port in 1..65535) &&
    apiHost !in setOf("localhost", "::1", "::", "0.0.0.0", "example.com", "example.net", "example.org") &&
    !apiHost.startsWith("127.") &&
    listOf(".localhost", ".local", ".example", ".invalid", ".test").none { apiHost.endsWith(it) }
require(apiConfigured || (allowOfflinePreview && !requireLiveAuth && momentoApiBaseUrl.isEmpty())) {
    "MOMENTO APK requires a configured public HTTPS API URL (-PmomentoApiBaseUrl=...). For an explicitly nonfunctional local UI preview only: -PmomentoAllowOfflinePreview=true -PmomentoRequireLiveAuth=false."
}
val escapedMomentoApiBaseUrl = momentoApiBaseUrl
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

android {
    namespace = "ru.momento.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "ru.momento.app"
        minSdk = 24
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 37
        versionName = "0.54.3"
        buildConfigField("String", "MOMENTO_API_BASE_URL", "\"$escapedMomentoApiBaseUrl\"")
        buildConfigField("boolean", "MOMENTO_API_CONFIGURED", apiConfigured.toString())
        buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", "\"$googleServerClientId\"")
        buildConfigField("String", "YANDEX_CLIENT_ID", "\"$yandexClientId\"")
        buildConfigField("String", "VK_CLIENT_ID", "\"$vkClientId\"")
        buildConfigField("boolean", "VK_CLIENT_CONFIGURED", (vkClientId.isNotBlank() && vkClientSecret.isNotBlank()).toString())
        manifestPlaceholders["YANDEX_CLIENT_ID"] = yandexClientId.ifBlank { "not-configured" }
        manifestPlaceholders["YANDEX_OAUTH_HOST"] = "oauth.yandex.ru"
        manifestPlaceholders["VKIDClientID"] = vkClientId
        manifestPlaceholders["VKIDClientSecret"] = vkClientSecret
        manifestPlaceholders["VKIDRedirectHost"] = "vk.ru"
        manifestPlaceholders["VKIDRedirectScheme"] = "vk$vkClientId"
    }

    // Release signing is opt-in; never store passwords or keystores in source control.
    signingConfigs {
        create("momentoRelease") {
            val storePath = providers.environmentVariable("MOMENTO_KEYSTORE").orNull
            if (!storePath.isNullOrBlank()) {
                storeFile = file(storePath)
                storePassword = providers.environmentVariable("MOMENTO_STORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("MOMENTO_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("MOMENTO_KEY_PASSWORD").orNull
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (!providers.environmentVariable("MOMENTO_KEYSTORE").orNull.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("momentoRelease")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.yandex.auth)
    implementation(libs.vkid)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.camera.view)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)

    testImplementation(libs.junit)
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
