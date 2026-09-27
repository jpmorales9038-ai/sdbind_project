plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.sdcardbind.manager"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sdcardbind.manager"
        minSdk = 26
        targetSdk = 34
        versionCode = 104
        versionName = "2.8.38"
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
            isMinifyEnabled = true
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
        freeCompilerArgs += "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
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
    val composeBom = platform("androidx.compose:compose-bom:2025.12.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")

    implementation("com.github.topjohnwu.libsu:core:6.0.0")

    // Baseline profile: le dice a Android qué clases/métodos compilar por adelantado en vez
    // de esperar a que el JIT se caliente solo con el uso — apunta directo al lag inicial
    // (app a tirones los primeros minutos tras reiniciar). Con esta sola dependencia, el
    // build ya fusiona automáticamente los baseline profiles que Compose/Material3 traen
    // empaquetados en sus propios AAR (compilados por Google con benchmarks reales); lo que
    // agregamos a mano en baseline-prof.txt es solo para las clases propias de esta app, que
    // ningún AAR de terceros puede cubrir.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    // Difuminado nativo del pill flotante (equivalente al backdrop-filter del WebUI).
    implementation("dev.chrisbanes.haze:haze:1.7.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
