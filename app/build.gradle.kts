import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hiddenApi)
}

// ==================== rustic 依赖版本 ====================

// 直接读 rustic_bridge/Cargo.toml，编译期写进 BuildConfig，设置页用来展示
val cargoTomlText = providers.fileContents(
    layout.projectDirectory.file("src/main/rustic_bridge/Cargo.toml")
).asText

fun cargoCrateVersion(text: String, crate: String): String {
    val escaped = Regex.escape(crate)
    // rustic_backend = { version = "0.7.0", default-features = false }
    Regex("""(?m)^\s*$escaped\s*=\s*\{[^}]*?version\s*=\s*"([^"]+)"""")
        .find(text)?.groupValues?.get(1)
        ?.let { return it }
    // rustic_core = "0.13.0"
    Regex("""(?m)^\s*$escaped\s*=\s*"([^"]+)"""")
        .find(text)?.groupValues?.get(1)
        ?.let { return it }
    return "unknown"
}

val rusticCoreVersion = cargoCrateVersion(cargoTomlText.getOrElse(""), "rustic_core")
val rusticBackendVersion = cargoCrateVersion(cargoTomlText.getOrElse(""), "rustic_backend")

android {
    namespace = "com.cc.libackupnext"
    compileSdk {
        version = release(37)
    }
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.cc.libackupnext"
        minSdk = 30
        targetSdk = 37
        // CI 用 -PversionName / -PversionCode 覆盖；本地不传就是 1 / 1.0
        versionCode = providers.gradleProperty("versionCode").orNull?.toIntOrNull() ?: 1
        versionName = providers.gradleProperty("versionName").orNull
            ?.takeIf { it.isNotBlank() } ?: "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 设置页展示用（值来自 Cargo.toml）
        buildConfigField("String", "RUSTIC_CORE_VERSION", "\"$rusticCoreVersion\"")
        buildConfigField("String", "RUSTIC_BACKEND_VERSION", "\"$rusticBackendVersion\"")
    }

    buildTypes {
        debug {
            // 调试包同时带 x86_64，方便模拟器跑
            ndk {
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
        }
        release {
            // 正式包只打 arm64-v8a
            ndk {
                abiFilters += "arm64-v8a"
            }
            // 只裁剪 + 优化，不混淆（混淆 androidx 会导致正式包启动闪退，见 proguard-rules.pro）
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
    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // cargo-ndk 会把依赖 crate 的 cdylib 一起拷进来，这里只保留我们自己的库
            excludes += "**/libcrc_fast-*.so"
            // 压缩 APK 里的 .so（安装时解压），能省一大截体积
            useLegacyPackaging = true
        }
        dex {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.material3.icons)

    implementation(libs.libsu.core)
    implementation(libs.libsu.service)
    implementation(libs.rikkax.parcelablelist)


    implementation(libs.hiddenApi.runtime)
    compileOnly(project(":hidden-api"))

}

// ==================== rustic JNI 库 ====================

val rustDir = rootProject.layout.projectDirectory.dir("app/src/main/rustic_bridge")
val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs")

/** 从 local.properties 的 sdk.dir 推出 NDK 路径，给 cargo-ndk 用。 */
val ndkHome: String? = run {
    System.getenv("ANDROID_NDK_HOME")?.takeIf { it.isNotBlank() }?.let { return@run it }

    val localProperties = rootProject.file("local.properties")
    if (!localProperties.exists()) return@run null

    val sdkDir = Properties().apply {
        localProperties.inputStream().use { load(it) }
    }.getProperty("sdk.dir") ?: return@run null

    val ndkRoot = file("$sdkDir/ndk")
    ndkRoot.listFiles()
        ?.filter { it.isDirectory }
        ?.maxByOrNull { it.name }
        ?.absolutePath
}

val cargoBinDir: String? = run {
    val home = File(System.getProperty("user.home"), ".cargo/bin")
    home.takeIf { it.isDirectory }?.absolutePath
}

val cargoCmd: String = run {
    System.getenv("CARGO")?.takeIf { it.isNotBlank() }?.let { return@run it }

    cargoBinDir?.let { dir ->
        listOf(File(dir, "cargo.exe"), File(dir, "cargo"))
            .firstOrNull { it.exists() }?.absolutePath
    } ?: "cargo"
}

// 默认两个 ABI；打正式包可以 `-PrustAbis=arm64-v8a` 只编 v8a，省一半时间
val rustAbis: List<String> = (providers.gradleProperty("rustAbis").orNull ?: "arm64-v8a,x86_64")
    .split(",")
    .map { it.trim() }
    .filter { it.isNotEmpty() }

val buildRust = tasks.register<Exec>("buildRust") {
    group = "build"
    description = "编译 rustic JNI 库（默认 arm64-v8a + x86_64，可用 -PrustAbis 指定）"

    workingDir = rustDir.asFile
    ndkHome?.let { environment("ANDROID_NDK_HOME", it) }
    cargoBinDir?.let {
        val path = System.getenv("PATH").orEmpty()
        environment("PATH", if (path.isBlank()) it else "$it${File.pathSeparator}$path")
    }

    commandLine(
        buildList {
            add(cargoCmd)
            add("ndk")
            rustAbis.forEach {
                add("-t")
                add(it)
            }
            add("-P")
            add("30")
            add("-o")
            add(jniLibsDir.asFile.absolutePath)
            add("build")
            add("--release")
        }
    )

    inputs.dir(rustDir.dir("src"))
    inputs.file(rustDir.file("Cargo.toml"))
    outputs.dir(jniLibsDir)
}

tasks.named("preBuild").configure {
    dependsOn(buildRust)
}