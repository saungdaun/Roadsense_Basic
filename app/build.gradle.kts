plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")

    // 🔥 KSP (gantikan KAPT)
    id("com.google.devtools.ksp")

    // Hilt
    id("com.google.dagger.hilt.android")
    id("androidx.navigation.safeargs.kotlin")

    // Parcelable
    id("kotlin-parcelize")
}

android {
    namespace = "zaujaani.roadsense"
    compileSdk = 34

    defaultConfig {
        applicationId = "zaujaani.roadsense"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // ⭐ Sangat direkomendasikan (lebih cepat)
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

// 🔥 Penting untuk Room migration & audit schema
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {

    // ---------------- CORE ----------------

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.8.2")

    // Splash
    implementation("androidx.core:core-splashscreen:1.0.1")

    // ---------------- LIFECYCLE ----------------

    val lifecycle = "2.7.0"

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:$lifecycle")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:$lifecycle")
    implementation("androidx.lifecycle:lifecycle-service:$lifecycle")

    // ---------------- NAVIGATION ----------------

    val nav = "2.7.6"

    implementation("androidx.navigation:navigation-fragment-ktx:$nav")
    implementation("androidx.navigation:navigation-ui-ktx:$nav")

    // ---------------- COROUTINES ----------------

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // ---------------- TIMBER ----------------

    implementation("com.jakewharton.timber:timber:5.0.1")

    // ---------------- LOCATION ----------------

    implementation("com.google.android.gms:play-services-location:21.2.0")

    // ---------------- OSM (MAP ENGINE) ----------------

    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("org.osmdroid:osmdroid-mapsforge:6.1.20")



    // ---------------- HILT ----------------

    val hilt = "2.48"

    implementation("com.google.dagger:hilt-android:$hilt")
    ksp("com.google.dagger:hilt-compiler:$hilt")

    // ---------------- ROOM ----------------

    val room = "2.6.1"

    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    // ---------------- TEST ----------------

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")
    implementation("androidx.gridlayout:gridlayout:1.0.0")

    implementation("com.google.code.gson:gson:2.10.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

}
