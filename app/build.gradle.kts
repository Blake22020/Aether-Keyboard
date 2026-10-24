plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.expressive.keyboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.expressive.keyboard"
        minSdk = 26
        targetSdk = 35
        versionCode = 14
        versionName = "11.1.0"
        vectorDrawables {
            useSupportLibrary = true
        }
        // в APK остаются только нужные языки ресурсов библиотек
        resourceConfigurations += listOf("ru", "en")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isDebuggable = true
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
        buildConfig = false
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/*.version"
            excludes += "/kotlin/**"
            excludes += "DebugProbesKt.bin"
        }
    }
}

composeCompiler {
    // меньше лишних перекомпозиций: лямбды и нестабильные параметры тоже пропускаются, если не изменились
    enableStrongSkippingMode = true
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3:1.3.0")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("io.coil-kt:coil-gif:2.6.0")

    // JavaScript для модов (плагинов со скриптами): чистая Java, работает в режиме интерпретатора
    implementation("org.mozilla:rhino:1.7.15")

    // ставит baseline-профили Compose при установке: быстрее первый показ клавиатуры и прокрутка
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
}
