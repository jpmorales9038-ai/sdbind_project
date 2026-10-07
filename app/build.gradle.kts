plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Sufijo de build que inyecta el CI en las pre-releases (p. ej. "-preview.57"); vacío en main y en
// compilaciones locales. Va dentro de versionName para que la app sepa qué pre-release es.
val buildSuffix: String = providers.gradleProperty("buildSuffix").getOrElse("")

android {
    namespace = "com.sdcardbind.manager"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sdcardbind.manager"
        minSdk = 26
        targetSdk = 34
        versionCode = 107
        versionName = "2.9.3$buildSuffix"
    }

    signingConfigs {
        create("stable") {
            storeFile = file("sdbind.keystore")
            storePassword = "SdBind!mod"
            keyAlias = "sdbind"
            keyPassword = "SdBind!mod"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            // R8 + recorte de recursos: APK más chico y código optimizado (las reglas están en
            // proguard-rules.pro). Firma con la misma keystore que debug, así que el
            // `pm install -r` desde el módulo sigue actualizando sin desinstalar.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("stable")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi"
        )
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.04.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    // Instala el baseline profile (src/main/baseline-prof.txt) sin depender de Play Store.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // Material 3 Expressive. La 1.4.0 estable deja las APIs Expressive internas, así que se
    // usa la línea 1.5.0-alpha. Ojo: desde alpha20 aprox. depende de Compose 1.12 alpha, que
    // exige compileSdk 37 + AGP 9.1; alpha18 sigue sobre Compose 1.11 (compileSdk 36, AGP 8.x).
    implementation("androidx.compose.material3:material3:1.5.0-alpha18")
    implementation("androidx.graphics:graphics-shapes:1.0.1")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")

    implementation("com.github.topjohnwu.libsu:core:6.0.0")


    debugImplementation("androidx.compose.ui:ui-tooling")
}
