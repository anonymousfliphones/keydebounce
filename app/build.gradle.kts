import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    id("com.android.application")
}

// Same NDK the daemon has always been built with (see CLAUDE.md).
val daemonNdk = "27.0.12077973"

android {
    namespace = "io.github.anonymousfliphones.keydebounce"
    compileSdk = 35
    ndkVersion = daemonNdk

    defaultConfig {
        applicationId = "io.github.anonymousfliphones.keydebounce"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

base {
    archivesName.set("keydebounce")
}

// Compiles ../keydebounce.c for armeabi-v7a with the NDK (the same command as
// the manual build) and bundles it with the files in ../sepolicy as assets/kd.
// The app copies these to /data/local/tmp/kd_dry and runs the install scripts
// as root.
abstract class BuildDaemon : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val source: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val bundled: ConfigurableFileCollection

    @get:Input
    abstract val clang: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val execOps: ExecOperations

    @TaskAction
    fun build() {
        val out = outputDir.get().asFile.resolve("kd")
        out.deleteRecursively()
        out.mkdirs()
        bundled.files.forEach { it.copyTo(out.resolve(it.name)) }

        val compiler = File(clang.get())
        if (!compiler.exists()) {
            throw GradleException("NDK compiler not found: $compiler\nInstall that NDK version with the SDK manager.")
        }
        execOps.exec {
            commandLine(
                compiler.absolutePath, "-O2", "-Wall", "-Wextra",
                "-o", out.resolve("keydebounce").absolutePath,
                source.get().asFile.absolutePath, "-llog",
            )
        }
    }
}

val osName: String = System.getProperty("os.name")
val ndkHost = when {
    osName.startsWith("Windows") -> "windows-x86_64"
    osName.startsWith("Mac") -> "darwin-x86_64"
    else -> "linux-x86_64"
}
val clangPath = "toolchains/llvm/prebuilt/$ndkHost/bin/armv7a-linux-androideabi21-clang" +
    if (osName.startsWith("Windows")) ".cmd" else ""

androidComponents {
    // sdkComponents.ndkDirectory has no value without a CMake/ndk-build setup,
    // so find the side-by-side NDK in the SDK directly.
    val ndkDir = sdkComponents.sdkDirectory.map { it.dir("ndk/$daemonNdk") }
    onVariants { variant ->
        val task = tasks.register<BuildDaemon>("buildDaemon${variant.name.replaceFirstChar { it.uppercase() }}") {
            source.set(rootProject.layout.projectDirectory.file("keydebounce.c"))
            bundled.from(rootProject.layout.projectDirectory.dir("sepolicy").asFileTree)
            clang.set(ndkDir.map { it.file(clangPath).asFile.absolutePath })
        }
        variant.sources.assets?.addGeneratedSourceDirectory(task, BuildDaemon::outputDir)
    }
}
