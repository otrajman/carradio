plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.carradio.app"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        targetSdk = 36
        // CI passes the run number so every Play upload has a fresh versionCode.
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "0.1.0"
    }

    // Two apps from one codebase: Car Radio (phone + Android Auto) and PelotonCB (phone
    // only, voice-activated group radio for cyclists, PROTOCOL §16). Shared protocol core,
    // audio, and Supabase I/O live in src/main; each flavor owns its service + UI.
    flavorDimensions += "app"
    productFlavors {
        create("carradio") {
            dimension = "app"
            applicationId = "com.carradio.app"
        }
        create("peloton") {
            dimension = "app"
            applicationId = "com.pelotoncb.app"
        }
    }

    // Play upload signing. Keystore path/passwords come from env (CI secrets) or
    // ~/.carradio/upload.keystore locally; falls back to unsigned for plain builds.
    val uploadKeystore = System.getenv("UPLOAD_KEYSTORE_PATH")
        ?: "${rootDir.parentFile}/.secrets/upload.keystore"
    val haveUploadKey = file(uploadKeystore).exists() &&
        System.getenv("KEYSTORE_PASSWORD") != null

    signingConfigs {
        if (haveUploadKey) {
            create("upload") {
                storeFile = file(uploadKeystore)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS") ?: "upload"
                keyPassword = System.getenv("KEY_PASSWORD") ?: System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (haveUploadKey) {
                signingConfig = signingConfigs.getByName("upload")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/io.netty.versions.properties"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    val supabaseBom = platform(libs.supabase.bom)
    implementation(supabaseBom)
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.storage)
    implementation(libs.supabase.realtime)
    implementation(libs.ktor.client.okhttp)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)

    "carradioImplementation"(libs.car.app)

    implementation(libs.play.services.location)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.serialization.json)
}
