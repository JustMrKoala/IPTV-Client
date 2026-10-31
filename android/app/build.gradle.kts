plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The TV interface is the same index.html and i18n.js the desktop version serves, copied in from web/ at build time
abstract class CopyWebTask : DefaultTask() {
    @get:InputFiles
    abstract val pages: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        pages.forEach { it.copyTo(File(out, it.name), overwrite = true) }
    }
}

val copyWeb = tasks.register<CopyWebTask>("copyWeb") {
    pages.from(rootProject.files("../web/index.html", "../web/i18n.js", "../web/remote.html"))
}

android {
    namespace = "app.lounge.tuner"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.lounge.tuner"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyWeb, CopyWebTask::outputDir)
    }
}

dependencies {
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    testImplementation("junit:junit:4.13.2")
}
