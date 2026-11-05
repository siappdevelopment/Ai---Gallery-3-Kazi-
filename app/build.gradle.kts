plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

android {
    namespace = "com.photovideo.gallerykz.memories"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.photovideo.gallerykz.memories"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    bundle {
        language {
            enableSplit = false
        }
    }


    flavorDimensions += "env"
    productFlavors {
        create("prod") {
            // Production reads the production ad config JSON (Remote Config parameter AppVersion_1_0).
            buildConfigField("String", "REMOTE_CONFIG_PARAM", "\"AppVersion_1_0\"")
            dimension = "env"
        }
        create("qa") {
            dimension = "env"
            versionNameSuffix = "-qa"
            // QA (Debug and Release) reads its own ad config JSON. Same Firebase app as prod (same application id), so the
            // separation is the parameter name in the one Remote Config of the project.
            // Remote Config parameter AppVersion_Q_1.
            buildConfigField("String", "REMOTE_CONFIG_PARAM", "\"AppVersion_Q_1\"")
            versionCode = 0 + (defaultConfig.versionCode ?: 0)
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    buildFeatures {
        viewBinding = true
        // BuildConfig.REMOTE_CONFIG_PARAM (set per flavor above) names the Remote Config parameter to fetch.
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// Room schemas are exported here so future migrations can be verified.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.glide)
    // Face detection for People & faces: the model is bundled, so it runs on the device without Play services.
    implementation(libs.mlkit.face.detection)
    implementation(libs.androidx.exifinterface)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    implementation(libs.androidx.fragment.ktx)
    // The launcher pager (gallery | home).
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("com.intuit.sdp:sdp-android:1.1.1")
    implementation("com.airbnb.android:lottie:6.6.7")

    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-crashlytics")
    implementation("com.google.firebase:firebase-config")
    implementation("com.google.android.gms:play-services-ads:23.6.0")
}