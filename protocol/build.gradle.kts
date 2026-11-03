plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    // Android Lint checks this module too (API levels: it runs on TVs back to Android 8).
    alias(libs.plugins.android.lint)
}

kotlin {
    explicitApi()
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}

lint {
    abortOnError = true
    warningsAsErrors = true
    checkAllWarnings = true
    lintConfig = rootProject.file("app/lint.xml")
}
