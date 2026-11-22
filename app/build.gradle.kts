import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import com.android.build.api.variant.FilterConfiguration.FilterType.ABI

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
    // Compose 1.12 / Coil 3.6 起要求 compileSdk 37；targetSdk 保持 35，
    // 避免本次依赖升级叠加运行时行为变更（root shell / 外部存储访问需单独回归）
    compileSdk = 37

    // SILK 解码器随源码编译，NDK r30 仍默认按 16KB 页对齐，CMake 脚本里也显式保留链接参数
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
                // v2 保证整文件覆盖与快速校验；v3 附带密钥轮换谱系，为日后换密钥留路。
                // v1 仅面向 Android 6 及以下（minSdk 24 用不到），v4 仅增量安装需要，均不启用。
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            // 调试包用独立 applicationId，与正式包可共存，避免签名不同导致互相覆盖
            applicationIdSuffix = "dev"
        }
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
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    // 按 ABI 分包：每个 APK 只带一套原生库，体积更小，用户下载对应架构即可。
    // 不生成 universal 包，避免分发时出现五个文件造成混淆。
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = false
        }
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

// 内置 Kotlin 的 jvmTarget 默认跟随 compileOptions.targetCompatibility，这里显式固定为 21，
// 与运行 Gradle 的 JDK 25 解耦；不跟到 25 是为了控制 ART 的脱糖负担与 R8 兼容性风险。
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

// 每个 ABI 分配独立的 versionCode 段（ABI 序号 * 10000 + 基础版本号），
// 避免四个包 versionCode 相同导致应用商店/安装器互相判定为旧版本。
val abiCodes = mapOf(
    "armeabi-v7a" to 1,
    "arm64-v8a" to 2,
    "x86" to 3,
    "x86_64" to 4,
)
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters.find { it.filterType == ABI }?.identifier
            abiCodes[abi]?.let { code ->
                output.versionCode.set(code * 10000 + (output.versionCode.get() ?: 0))
            }
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)

    testImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
