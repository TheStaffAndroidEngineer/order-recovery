plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinCompose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room3)
}
android {
    namespace = "staff.android.orders"
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "staff.android.orders"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        val selected = providers.gradleProperty("testClass").orNull
        if (selected != null) testInstrumentationRunnerArguments["class"] = selected
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_26
        targetCompatibility = JavaVersion.VERSION_26
    }
}
kotlin {
    jvmToolchain(27)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_26) }
}
dependencies {
    implementation(libs.workRuntime)
    constraints { implementation(libs.workRoomRuntime) }
    androidTestImplementation(libs.workTesting)
    implementation(libs.serializationJson)
    implementation(libs.room3Runtime)
    implementation(libs.sqliteBundled)
    implementation(project(":protocol"))
    ksp(libs.room3Compiler)
    androidTestImplementation(libs.room3Testing)
    implementation(libs.androidxActivityCompose)
    implementation(libs.androidxLifecycleViewModel)
    implementation(libs.androidxLifecycleRuntimeCompose)
    implementation(libs.coroutinesAndroid)
    val composeBom = platform(libs.composeBom)
    implementation(composeBom)
    implementation(libs.composeUi)
    implementation(libs.composeFoundation)
    implementation(libs.composeMaterial3)
    debugImplementation(libs.composeTestManifest)
    androidTestImplementation(composeBom)
    androidTestImplementation(libs.composeTestJunit4)
    androidTestImplementation(libs.androidxTestJunit)
    androidTestImplementation(libs.androidxTestRunner)
}

room3 { schemaDirectory("$projectDir/schemas") }
