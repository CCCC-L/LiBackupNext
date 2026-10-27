plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.cc.hidden_api"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 30

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)

    annotationProcessor(libs.hiddenApi.annotation.processor)
    compileOnly(libs.hiddenApi.annotation)
}