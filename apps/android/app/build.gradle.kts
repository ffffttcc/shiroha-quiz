plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yiqiu.shirohaquiz"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        targetSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "variant"

    productFlavors {
        create("web") {
            dimension = "variant"
            applicationId = "com.yiqiu.shirohaquiz"
            versionCode = 56
            versionName = "0.8.9.1-alpha"
        }
        create("native") {
            dimension = "variant"
            testInstrumentationRunner = "com.yiqiu.shirohaquiz.state.QuizRepositoryCloudRestoreSafetyInstrumentation"
            applicationId = "com.reqir.shirohaquiz"
            versionCode = 222
            versionName = "0.9.16.6"
        }
    }

    signingConfigs {
        // 固定 debug keystore（仅 debug 用途，公开口令 android）：让每次 CI 构建签名一致，
        // 后续 pm install -r 可直接覆盖升级，不必卸载清数据。与发布密钥无关。
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // release 刻意复用同一把 debug keystore：
        // 1) 与现有 debug 包**签名一致** → 正式包可直接 pm install -r 覆盖升级，不必卸载、不丢题库；
        // 2) 仓库不提供 keystore.properties，原先那套「读 properties 再签名」在 CI 里等于没有签名配置。
        // 真正的对外发布密钥不在仓库里，需要时另立 config，不要改写这里。
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += "-XXLanguage:+BreakContinueInInlineLambdas"
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    sourceSets.getByName("testNative").java.srcDir("src/androidTestNative/java")
    testOptions.unitTests.isIncludeAndroidResources = true

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

val exportWebReleaseApk by tasks.registering(Copy::class) {
    val webVersionName = android.productFlavors.getByName("web").versionName
    from(layout.buildDirectory.file("outputs/apk/web/release/app-web-release.apk"))
    into(layout.buildDirectory.dir("outputs/shiroha-quiz"))
    rename { "Shiroha-Quiz-v$webVersionName-web-release.apk" }
}

val exportNativeReleaseApk by tasks.registering(Copy::class) {
    val nativeVersionName = android.productFlavors.getByName("native").versionName
    from(layout.buildDirectory.file("outputs/apk/native/release/app-native-release.apk"))
    into(layout.buildDirectory.dir("outputs/shiroha-quiz"))
    rename { "Shiroha-Quiz-v$nativeVersionName-native-release.apk" }
}

afterEvaluate {
    tasks.named("assembleWebRelease") {
        finalizedBy(exportWebReleaseApk)
    }
    tasks.named("assembleNativeRelease") {
        finalizedBy(exportNativeReleaseApk)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    add("nativeImplementation", "androidx.work:work-runtime-ktx:2.9.1")
    testImplementation("junit:junit:4.13.2")
    add("testNativeImplementation", "net.sf.kxml:kxml2:2.3.0")
    add("testNativeImplementation", "org.robolectric:robolectric:4.12.2")
    add("testNativeImplementation", "org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
