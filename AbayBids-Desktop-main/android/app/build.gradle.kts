plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.abaybids.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.abaybids.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL,ASL,LICENSE,LICENSE.txt,NOTICE,NOTICE.txt,DEPENDENCIES,*.md}"
        }
    }
    // We intentionally let WorkManager auto-initialize via androidx.startup and
    // supply our custom Configuration through the Application's
    // Configuration.Provider interface. The lint check RemoveWorkManagerInitializer
    // wants us to instead disable auto-init and call WorkManager.initialize
    // manually — but that pattern is exactly what caused the original launch
    // crash (IllegalStateException: WorkManager is already initialized).
    // So we suppress that specific lint check.
    lint {
        abortOnError = false
        checkReleaseBuilds = false
        disable += "RemoveWorkManagerInitializer"
    }
}

dependencies {
    // AndroidX core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.gridlayout:gridlayout:1.0.0")
    implementation("com.google.android.material:material:1.12.0")
    // Android 12+ native splash screen (instead of a blank white flash on launch)
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Lifecycle / ViewModel / LiveData
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // WorkManager (periodic background check)
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // ===== Firebase (links Windows + Android apps via shared Firestore + free FCM) =====
    // We intentionally do NOT apply the `com.google.gms.google-services` plugin —
    // instead we initialize Firebase programmatically from
    // `assets/firebase-config.json` (see FirebaseSync.kt). This lets the APK
    // build + run standalone (no google-services.json required at build time)
    // and become "linked" the moment the user drops real credentials into
    // `assets/firebase-config.json`.
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("com.google.firebase:firebase-messaging-ktx")
    implementation("com.google.firebase:firebase-storage-ktx")
}
