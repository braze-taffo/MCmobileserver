import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// JRE mobile 构建产物（AngelAuraMC 维护的 PojavLauncher 系 OpenJDK，Android/bionic 原生编译）
// 21 覆盖 MC 1.17–1.21.x；25 覆盖 MC 26.x（2026 起日期式版本要求 Java 25）
data class JreSpec(val major: Int, val url: String, val sha256: String)

val jreArtifacts = mapOf(
    "arm64-v8a" to listOf(
        JreSpec(
            21,
            "https://github.com/AngelAuraMC/angelauramc-openjdk-build/releases/download/download_jre21/jre21-android-arm64.tar.xz",
            "8d41ec401ee59f7722df60ed991f81ad146e130452804bfdd8a05d3436f7bbfe",
        ),
        JreSpec(
            25,
            "https://github.com/AngelAuraMC/angelauramc-openjdk-build/releases/download/download_jre25/jre25-android-arm64.tar.xz",
            "d3eb7afe2240c26728a1bb440502c5f18ac3883e932d202dd7f0c9bcbbce4c37",
        ),
    ),
    "x86_64" to listOf(
        JreSpec(
            21,
            "https://github.com/AngelAuraMC/angelauramc-openjdk-build/releases/download/download_jre21/jre21-android-x86_64.tar.xz",
            "cb88723961f5f9ad63afa1f212eb199816c27cabfd7dc66567bde1d8fb69713b",
        ),
        JreSpec(
            25,
            "https://github.com/AngelAuraMC/angelauramc-openjdk-build/releases/download/download_jre25/jre25-android-x86_64.tar.xz",
            "7fca862ee1b2d5fe23cd9c9c3d9b7ad3c241947ad1a6cc9464ef2e674867105d",
        ),
    ),
)

android {
    namespace = "com.mcmobile.server"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.mcmobile.server"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"

        externalNativeBuild {
            cmake {
                cppFlags("")
                arguments("-DANDROID_STL=none")
            }
        }
    }

    // debug 额外打包 x86_64 供 PC 模拟器 E2E；release 仅 arm64
    buildTypes {
        debug {
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            ndk { abiFilters += listOf("arm64-v8a") }
            val keystoreProps = File(rootDir, "release-signing.properties")
            if (keystoreProps.exists()) {
                val props = Properties().apply { keystoreProps.inputStream().use { load(it) } }
                for (key in listOf("storeFile", "storePassword", "keyAlias", "keyPassword")) {
                    require(!props.getProperty(key).isNullOrBlank()) { "Missing release signing property: $key" }
                }
                signingConfig = signingConfigs.create("release") {
                    storeFile = File(rootDir, props.getProperty("storeFile"))
                    storePassword = props.getProperty("storePassword")
                    keyAlias = props.getProperty("keyAlias")
                    keyPassword = props.getProperty("keyPassword")
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        jniLibs {
            // frpc is an executable packaged as a native library; it must be extracted.
            useLegacyPackaging = true
            keepDebugSymbols += "**/libfrpc.so"
            // mobile 构建已做过符号剥离；AGP 再 strip 会损坏 libjvm.so
            keepDebugSymbols += "**/libjvm*.so"
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

// Keep debug builds usable on a fresh checkout, but never distribute an unsigned/debug-signed release.
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst {
        check(rootProject.file("release-signing.properties").isFile) {
            "Release signing is required. Create release-signing.properties from release-signing.properties.example."
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.navigation.compose)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.datastore.preferences)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

fun sha256Of(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/** JRE25 的 libjvm 依赖 libc++_shared.so（NDK 惯例由宿主 App 提供） */
fun ndkLibcxx(abi: String, triple: String): File {
    val ndkDir = File(android.sdkDirectory, "ndk/${android.ndkVersion}")
    val host = listOf(
        "windows-x86_64", "linux-x86_64", "darwin-x86_64", "darwin-arm64",
    ).first { File(ndkDir, "toolchains/llvm/prebuilt/$it").exists() }
    val lib = File(
        ndkDir, "toolchains/llvm/prebuilt/$host/sysroot/usr/lib/$triple/libc++_shared.so",
    )
    check(lib.exists()) { "libc++_shared.so not found in NDK for $triple" }
    return lib
}

fun downloadTo(url: String, target: File) {
    val tmp = File(target.parentFile, target.name + ".part")
    URI(url).toURL().openStream().use { input ->
        tmp.outputStream().use { output -> input.copyTo(output) }
    }
    check(tmp.renameTo(target)) { "rename failed: $tmp" }
}

/**
 * 用系统 tar 解包。commons-compress 曾把这个构建的 tar.xz 解出截断的文件，
 * 故统一走外部 tar（Windows 10+ 自带 bsdtar，macOS/Linux 均有）。
 * 注意：不要 inheritIO——在此环境下会导致子进程死锁；显式丢弃 stdin、读完 stdout。
 */
fun extractTarXz(tarFile: File, targetDir: File) {
    targetDir.mkdirs()
    // bin/、legal/ 不打包进应用；bsdtar 对含非 ASCII 的路径在 legal/ 上会失败，故一并排除
    val proc = ProcessBuilder(
        "tar", "-xJf", tarFile.absolutePath, "-C", targetDir.absolutePath,
        "--exclude", "./bin/*", "--exclude", "./legal/*",
    )
        .redirectErrorStream(true)
        .start()
    val output = proc.inputStream.bufferedReader().readText()
    check(proc.waitFor() == 0) { "tar -xJf failed (${proc.exitValue()}): $output" }
}

/**
 * 下载并拆分各 JRE：
 *  - lib/server/libjvm.so → jniLibs/libjvm<major>.so（nativeLibraryDir 可执行）
 *  - 其余（去掉 bin/、legal/）→ assets/jre/<abi>/<major>.zip，运行期解压到 filesDir
 */
val prepareJreAssets by tasks.registering {
    group = "jre"
    description = "Download, verify and repack the Android OpenJDK runtimes"

    doLast {
        val cacheDir = rootProject.layout.buildDirectory.dir("jre").get().asFile.apply { mkdirs() }
        for ((abi, specs) in jreArtifacts) {
            for (spec in specs) {
                val marker = File(cacheDir, "$abi-${spec.major}.marker")

                val isMain = abi == "arm64-v8a"
                val jniDir = File(
                    projectDir,
                    if (isMain) "src/main/jniLibs/$abi" else "src/debug/jniLibs/$abi",
                )
                val libjvmDst = File(jniDir, "libjvm${spec.major}.so")
                val assetsDir = File(
                    projectDir,
                    if (isMain) "src/main/assets/jre/$abi" else "src/debug/assets/jre/$abi",
                )
                val zipDst = File(assetsDir, "${spec.major}.zip")
                // JRE25 需要 libc++_shared.so
                val triple = if (abi == "arm64-v8a") "aarch64-linux-android" else "x86_64-linux-android"
                val libcxxDst = if (spec.major == 25) File(jniDir, "libc++_shared.so") else null

                val upToDate = marker.exists() && marker.readText().trim() == spec.sha256 &&
                        libjvmDst.exists() && zipDst.exists() &&
                        (libcxxDst == null || libcxxDst.exists())
                if (upToDate) continue

                val tarFile = File(cacheDir, "$abi-${spec.major}.tar.xz")
                if (!tarFile.exists() || sha256Of(tarFile) != spec.sha256) {
                    logger.lifecycle("Downloading JRE${spec.major} ($abi) ...")
                    downloadTo(spec.url, tarFile)
                    val actual = sha256Of(tarFile)
                    check(actual == spec.sha256) { "SHA-256 mismatch for $abi java${spec.major}: $actual" }
                }

                val extractDir = File(cacheDir, "extracted-$abi-${spec.major}")
                if (!File(extractDir, "lib/modules").exists()) {
                    extractDir.deleteRecursively()
                    extractTarXz(tarFile, extractDir)
                }

                val libjvm = File(extractDir, "lib/server/libjvm.so")
                check(libjvm.exists()) { "libjvm.so missing in $abi java${spec.major} archive" }
                libjvmDst.parentFile.mkdirs()
                libjvm.copyTo(libjvmDst, overwrite = true)
                libcxxDst?.let { ndkLibcxx(abi, triple).copyTo(it, overwrite = true) }

                assetsDir.mkdirs()
                ZipOutputStream(zipDst.outputStream().buffered(1 shl 16)).use { zos ->
                    extractDir.walkTopDown().filter { it.isFile }.forEach { f ->
                        val rel = f.relativeTo(extractDir).invariantSeparatorsPath
                        if (rel == "lib/server/libjvm.so" ||
                            rel.startsWith("bin/") || rel.startsWith("legal/")
                        ) return@forEach
                        zos.putNextEntry(ZipEntry(rel))
                        f.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                    zos.putNextEntry(ZipEntry("mcs-jre-info.txt"))
                    zos.write("abi=$abi\njava=${spec.major}\nsha256=${spec.sha256}\n".toByteArray())
                    zos.closeEntry()
                }

                marker.writeText(spec.sha256)
                logger.lifecycle(
                    "JRE $abi java${spec.major} ready: libjvm=${libjvmDst.length() / 1048576}MB " +
                            "assetsZip=${zipDst.length() / 1048576}MB",
                )
            }
        }
    }
}

tasks.named("preBuild") { dependsOn(prepareJreAssets) }

// Official Android executable, pinned and verified before packaging.
val prepareFrpc by tasks.registering {
    val version = "0.71.0"
    val digest = "845b486c63686990e671f13cc5e3bd130ce7be659ab3c6f043008353451858cb"
    val destination = file("src/main/jniLibs/arm64-v8a/libfrpc.so")
    val license = file("src/main/assets/frp/LICENSE")
    inputs.property("sha256", digest)
    outputs.files(destination, license)
    doLast {
        val cache = rootProject.layout.buildDirectory.dir("frp").get().asFile.apply { mkdirs() }
        val archive = File(cache, "frp_${version}_android_arm64.tar.gz")
        if (!archive.exists() || sha256Of(archive) != digest) {
            downloadTo("https://github.com/fatedier/frp/releases/download/v$version/${archive.name}", archive)
        }
        check(sha256Of(archive) == digest) { "FRP SHA-256 mismatch" }
        val process = ProcessBuilder("tar", "-xzf", archive.absolutePath, "-C", cache.absolutePath)
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "FRP extraction failed: $output" }
        val extracted = File(cache, "frp_${version}_android_arm64")
        destination.parentFile.mkdirs()
        File(extracted, "frpc").copyTo(destination, overwrite = true)
        license.parentFile.mkdirs()
        File(extracted, "LICENSE").copyTo(license, overwrite = true)
    }
}
tasks.named("preBuild") { dependsOn(prepareFrpc) }

tasks.withType<Test>().configureEach {
    testLogging { events("failed"); showStackTraces = true }
    // Workaround：项目路径含非 ASCII 字符时，Gradle 传给 Test worker 的 pathing-jar
    // 解码失败导致 ClassNotFoundException。把项目目录下的 classpath 条目复制到
    // ASCII 临时路径后再运行。
    doFirst {
        val projectPath = rootProject.projectDir.absolutePath
        val asciiBase = File(
            System.getenv("TEMP") ?: System.getProperty("java.io.tmpdir"),
            "mcs-test-cp/$name",
        )
        asciiBase.deleteRecursively()
        asciiBase.mkdirs()
        val mapped = classpath.map { entry ->
            if (entry.absolutePath.startsWith(projectPath) && entry.exists()) {
                val copy = File(asciiBase, entry.name)
                if (entry.isDirectory) entry.copyRecursively(copy, overwrite = true)
                else entry.copyTo(copy, overwrite = true)
                copy
            } else entry
        }
        classpath = files(mapped)
        logger.lifecycle("TEST CLASSPATH: ${mapped.size} entries (non-ASCII workaround: $asciiBase)")
    }
}
