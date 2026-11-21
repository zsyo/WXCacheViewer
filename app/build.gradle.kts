import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 签名材料：CI 走环境变量，本地走根目录的 keystore.properties。
// 两者都缺失时（比如别人 clone 下来只跑 debug、或 fork 提 PR）不签名，构建照常进行。
val keystoreProps = Properties().apply {
    val propsFile = rootProject.file("keystore.properties")
    if (propsFile.exists()) propsFile.inputStream().use { load(it) }
}
val releaseKeystore = (System.getenv("KEYSTORE_FILE") ?: keystoreProps.getProperty("storeFile"))
    ?.let { rootProject.file(it) }
val hasReleaseKey = releaseKeystore?.exists() == true

android {
    namespace = "top.aico.wxcacheviewer"
    compileSdk = 35

    // SILK 解码器随源码编译，NDK r28 起默认按 16KB 页对齐
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "top.aico.wxcacheviewer"
        minSdk = 24
        targetSdk = 35
        // CI 打 tag 时由 VERSION_NAME / VERSION_CODE 注入，本地构建用下面的默认值
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "0.1"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                    ?: keystoreProps.getProperty("storePassword")
                keyAlias = System.getenv("KEY_ALIAS") ?: keystoreProps.getProperty("keyAlias")
                keyPassword = System.getenv("KEY_PASSWORD")
                    ?: keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else null
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
    // AGP 9 内置 Kotlin：编译选项移到顶层 kotlin DSL，不再是 android.kotlinOptions
    buildFeatures {
        compose = true
    }
    testOptions {
        // 单测里 AppLog 会调用 android.util.Log，未 mock 时让它返回默认值而不是抛异常
        unitTests.isReturnDefaultValues = true
    }
    externalNativeBuild {
        cmake {
            // SILK 解码器与 LAME 的源码构建脚本
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }
}

// 内置 Kotlin 的 jvmTarget 默认跟随 compileOptions.targetCompatibility，这里显式固定为 17，
// 让 ART 的脱糖负担保持与升级前一致（与运行 Gradle 的 JDK 21 无关）。
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)

    testImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
