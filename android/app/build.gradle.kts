plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.znxsgl.student"
    compileSdk = 34

    configurations.all {
        exclude(group = "org.jetbrains", module = "annotations-java5")
    }

    defaultConfig {
        applicationId = "com.znxsgl.student"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.3"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.fragment)
    implementation(libs.viewpager2)
    implementation(libs.constraintlayout)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)
    implementation(libs.markwon.core)
    implementation(libs.markwon.inline.parser)
    implementation(libs.markwon.html)
    implementation(libs.markwon.syntax.highlight)
    implementation(libs.markwon.ext.latex)
    implementation(libs.markwon.ext.tables)
    annotationProcessor(libs.prism4j.bundler)
    implementation(libs.glide)
    annotationProcessor(libs.glide.compiler)
    implementation(libs.photoview)
}
