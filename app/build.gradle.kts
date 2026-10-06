import org.gradle.api.execution.TaskExecutionGraph
import java.io.FileInputStream
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties =
    Properties().apply {
        if (keystorePropertiesFile.exists()) {
            FileInputStream(keystorePropertiesFile).use { load(it) }
        }
    }
val hasFixedSigning = keystorePropertiesFile.exists()

if (!hasFixedSigning) {

    logger.warn(
        "keystore.properties が無い。debug は SDK 既定の debug 鍵、" +
            "release は署名されず app-release-unsigned.apk になる" +
            "（固定鍵で入れた実機へは上書きできない）。",
    )
}

val localPropertiesFile = rootProject.file("local.properties")
val localProperties =
    Properties().apply {
        if (localPropertiesFile.exists()) {
            FileInputStream(localPropertiesFile).use { load(it) }
        }
    }

val dotEnv: Map<String, String> =
    rootProject.file(".env").takeIf { it.exists() }
        ?.readLines()
        ?.mapNotNull { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
            val eq = line.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            line.substring(0, eq).trim() to line.substring(eq + 1).trim()
        }
        ?.toMap() ?: emptyMap()

fun cloudSecret(
    envKey: String,
    localPropsKey: String,
): String =
    dotEnv[envKey]?.takeIf { it.isNotBlank() }
        ?: localProperties.getProperty(localPropsKey)?.takeIf { it.isNotBlank() }
        ?: ""

val sealedRequired =
    !cloudSecret("ANONEN_SEALED_REQUIRED", "anonen.sealed.required").equals("false", ignoreCase = true)

val cloudUrl = cloudSecret("ANONEN_CLOUD_URL", "anonen.cloud.url")

val acceptedDigestsFile = rootProject.file("accepted-digests.txt")
val acceptedImageDigests =
    (if (acceptedDigestsFile.exists()) acceptedDigestsFile.readLines() else emptyList())
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }

if (cloudSecret("ANONEN_ACCEPTED_IMAGE_DIGESTS", "anonen.cloud.acceptedImageDigests").isNotBlank()) {
    throw GradleException(
        "ANONEN_ACCEPTED_IMAGE_DIGESTS は accepted-digests.txt が持ちます。" +
            ".env / local.properties の該当行を削除してください。",
    )
}

val malformedDigests = acceptedImageDigests.filterNot { Regex("^sha256:[0-9a-f]{64}$").matches(it) }
if (malformedDigests.isNotEmpty()) {
    throw GradleException(
        "accepted-digests.txt の形式が不正です: ${malformedDigests.joinToString(", ")}。" +
            "sha256: + 64 桁の 16 進を 1 行に 1 つ記述してください。",
    )
}

val releaseArtifactTask =
    Regex("^(assemble|bundle|package|install)Release$|^(package|sign)Release(Bundle|UniversalApk)$")

gradle.taskGraph.whenReady(
    Action<TaskExecutionGraph> {
        val buildingRelease = allTasks.any { task -> releaseArtifactTask.matches(task.name) }

        if (buildingRelease && !sealedRequired) {
            throw GradleException(
                "release ビルドでは ANONEN_SEALED_REQUIRED=false を使用できません。" +
                    ".env の該当行を削除してください。",
            )
        }

        if (buildingRelease && cloudUrl.isBlank()) {
            throw GradleException(
                "release ビルドには ANONEN_CLOUD_URL が必要です。.env に指定してください。",
            )
        }

        if (buildingRelease && acceptedImageDigests.isEmpty()) {
            throw GradleException(
                "release ビルドには accepted-digests.txt に digest が 1 行以上必要です。",
            )
        }
    },
)

if (cloudSecret("ANONEN_SUPABASE_URL", "anonen.supabase.url").isBlank()) {
    logger.warn(
        "ANONEN_SUPABASE_URL が未設定のためクラウドのサインインは無効です。",
    )
}

val sherpaOnnxVersion = "1.13.3"
val sherpaOnnxAar = file("libs/sherpa-onnx-$sherpaOnnxVersion-no-tts.aar")
if (!sherpaOnnxAar.exists()) {
    throw GradleException(
        "app/libs/${sherpaOnnxAar.name} がありません。\n" +
            "  ANDROID_NDK_HOME=<NDK r29> tools/build-sherpa-onnx-no-tts.sh app/libs/${sherpaOnnxAar.name}\n" +
            "で作るか、作ったものを app/libs/ に置いてください" +
            "（上流が配っている sherpa-onnx-$sherpaOnnxVersion.aar は使えません）。",
    )
}

val sherpaOnnxTtsMarkers =
    listOf("espeak-ng", "espeak_", "ESPEAK_", "phontab", "phonindex", "intonations", "piper")
val sherpaOnnxRequiredLibraries =
    listOf("arm64-v8a", "armeabi-v7a").flatMap { abi ->
        listOf("jni/$abi/libsherpa-onnx-jni.so", "jni/$abi/libonnxruntime.so")
    }

fun containsBytes(
    haystack: ByteArray,
    needle: ByteArray,
): Boolean {
    if (needle.isEmpty() || haystack.size < needle.size) return false
    val first = needle[0]
    var index = 0
    val last = haystack.size - needle.size
    while (index <= last) {
        if (haystack[index] == first) {
            var offset = 1
            while (offset < needle.size && haystack[index + offset] == needle[offset]) offset++
            if (offset == needle.size) return true
        }
        index++
    }
    return false
}

val sherpaOnnxChecked = file("libs/.${sherpaOnnxAar.name}.checked")
val sherpaOnnxFingerprint = "${sherpaOnnxAar.length()}:${sherpaOnnxAar.lastModified()}"
if (!sherpaOnnxChecked.exists() || sherpaOnnxChecked.readText().trim() != sherpaOnnxFingerprint) {
    ZipFile(sherpaOnnxAar).use { zip ->
        val names = zip.entries().asSequence().map { it.name }.toSet()
        val missing = sherpaOnnxRequiredLibraries.filterNot { it in names }
        if (missing.isNotEmpty()) {
            throw GradleException("app/libs/${sherpaOnnxAar.name} に $missing がありません。")
        }
        for (entry in zip.entries().asSequence().filter { it.name.endsWith(".so") }) {
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            val found = sherpaOnnxTtsMarkers.filter { containsBytes(bytes, it.toByteArray()) }
            if (found.isNotEmpty()) {
                throw GradleException(
                    "app/libs/${sherpaOnnxAar.name} の ${entry.name} に読み上げ（TTS）の" +
                        "コードが入っています（$found）。espeak-ng（GPL）を含む AAR は使えません。" +
                        "tools/build-sherpa-onnx-no-tts.sh で作り直してください。",
                )
            }
        }
    }
    sherpaOnnxChecked.writeText("$sherpaOnnxFingerprint\n")
}

fun gitShortHash(): String {
    return runCatching {
        val proc =
            ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                .directory(rootDir)
                .redirectErrorStream(true)
                .start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        if (proc.waitFor() == 0 && out.isNotEmpty()) out else "unknown"
    }.getOrDefault("unknown")
}
android {
    namespace = "net.anonen.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.anonen.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 215
        versionName = "1.0.88"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        buildConfigField("String", "GIT_HASH", "\"${gitShortHash()}\"")

        buildConfigField(
            "String",
            "ANONEN_SUPABASE_URL",
            "\"${cloudSecret("ANONEN_SUPABASE_URL", "anonen.supabase.url")}\"",
        )
        buildConfigField(
            "String",
            "ANONEN_SUPABASE_ANON_KEY",
            "\"${cloudSecret("ANONEN_SUPABASE_ANON_KEY", "anonen.supabase.anonKey")}\"",
        )
        buildConfigField(
            "String",
            "ANONEN_CLOUD_URL",
            "\"${
                cloudSecret("ANONEN_CLOUD_URL", "anonen.cloud.url")
                    .ifBlank { "https://api.anonen.net" }
            }\"",
        )

        buildConfigField("boolean", "SEALED_REQUIRED", sealedRequired.toString())

        buildConfigField(
            "String",
            "ANONEN_ACCEPTED_IMAGE_DIGESTS",
            "\"${acceptedImageDigests.joinToString(",")}\"",
        )
    }

    signingConfigs {
        if (hasFixedSigning) {
            create("fixed") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        debug {
            if (hasFixedSigning) signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
            if (hasFixedSigning) signingConfig = signingConfigs.getByName("fixed")

            buildConfigField("boolean", "SEALED_REQUIRED", "true")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        val useDevFeatures =
            file("src/dev/java").exists() && dotEnv["ANONEN_EDITION"] != "product"
        getByName("main").java.srcDir(if (useDevFeatures) "src/dev/java" else "src/stub/java")
        if (useDevFeatures) getByName("test").java.srcDir("src/devTest/java")

        getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/notice"))
    }

    packaging {
        jniLibs {

            useLegacyPackaging = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

val copyNoticeToAssets by tasks.registering(Copy::class) {
    from(rootProject.file("NOTICE.md"))

    from(rootProject.file("THIRD-PARTY-NOTICES.md"))
    into(layout.buildDirectory.dir("generated/notice"))
}

val noticeFiles = listOf("NOTICE.md", "THIRD-PARTY-NOTICES.md").map { rootProject.file(it) }
tasks.named("preBuild") {
    dependsOn(copyNoticeToAssets)
    doFirst {
        noticeFiles.forEach { file ->
            if (!file.isFile) {
                throw GradleException("${file.name} がありません。第三者の著作権表示を載せずに APK は作りません。")
            }
        }
    }
}

tasks.register("listReleaseRuntimeArtifacts") {
    val artifacts =
        configurations.named("releaseRuntimeClasspath").map { configuration ->
            configuration.incoming.artifactView { lenient(true) }.artifacts.artifacts
                .mapNotNull { artifact ->
                    val id = artifact.id.componentIdentifier
                    if (id is org.gradle.api.artifacts.component.ModuleComponentIdentifier) {
                        "${id.group}:${id.module}:${id.version}"
                    } else {
                        null
                    }
                }
                .distinct()
                .sorted()
        }
    doLast { artifacts.get().forEach { println(it) } }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.bouncycastle.prov)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(":sherpa-onnx-1.13.3-no-tts@aar")
}

tasks.withType<Test>().configureEach {
    inputs.dir("src/main/res").withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("mainRes")
    inputs.dir("src/main/java").withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("mainJava")
}
