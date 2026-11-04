plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kakario.lightbrowser"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kakario.lightbrowser"
        minSdk = 26
        targetSdk = 35
        // GitHub 자동 빌드 번호를 버전으로 사용 (새 APK가 기존 앱 위에 업데이트되도록)
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        // 갤럭시 S24·폴드5 등 64비트 폰만 (번역 엔진 용량 줄이기)
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        // 고정 디버그 키 — 빌드마다 서명이 바뀌어 재설치가 필요해지는 문제 방지
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // 실시간 만화 번역: 글자 인식(구글 플레이 서비스가 처음 쓸 때 받음), 번역(모델은 쓸 때 받음), 고급 번역(모델은 따로 받음)
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mediapipe:tasks-genai:0.10.27")
    // 만화 전용 글자 인식(manga-ocr) 실행 엔진
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
}
