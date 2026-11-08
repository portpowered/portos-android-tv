import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

// Each environment is a product flavor whose values come from environments/<env>.properties.
// The values are public configuration (API URL, OAuth client id, Firebase app options), not secrets.
val environments = listOf("pilot", "local")

fun environment(name: String): Properties = Properties().apply {
    file("environments/$name.properties").inputStream().use { load(it) }
}

android {
    namespace = "com.portoperatingsystem.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.portoperatingsystem.tv"
        minSdk = 26
        targetSdk = 35
        versionCode = 18
        versionName = "0.8.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "environment"
    productFlavors {
        environments.forEach { name ->
            create(name) {
                dimension = "environment"
                val values = environment(name)
                listOf(
                    "API_BASE_URL",
                    "OAUTH_CLIENT_ID",
                    "PLUGIN_ID",
                    "FIREBASE_PROJECT_ID",
                    "FIREBASE_APPLICATION_ID",
                    "FIREBASE_API_KEY",
                    "FIREBASE_SENDER_ID"
                ).forEach { key ->
                    val value =
                        checkNotNull(values.getProperty(key)) { "environments/$name.properties is missing $key" }
                    buildConfigField("String", key, "\"$value\"")
                }
            }
        }
    }

    // scripts/release.sh fetches the keystore from Secret Manager and passes it in through the environment.
    // Without it the release build is left unsigned and cannot be installed.
    val keystore = System.getenv("PORTOS_TV_KEYSTORE")
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("PORTOS_TV_KEYSTORE_PASSWORD")
                keyAlias = "portos-android-tv"
                keyPassword = System.getenv("PORTOS_TV_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        checkAllWarnings = true
        checkDependencies = true
        checkReleaseBuilds = true
        explainIssues = true
        lintConfig = file("lint.xml")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = false
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":protocol"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.androidx.work)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)
    implementation(libs.zxing.core)
    implementation(libs.dadb)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit)
}
