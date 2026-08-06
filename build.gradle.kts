import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    id("org.jetbrains.compose") version "1.10.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
}

group = "ai.rever.boss.plugin.dynamic"
version = "1.9.4"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Auto-detect CI environment
val useLocalDependencies = System.getenv("CI") != "true"
val bossPluginApiPath = "../boss-plugin-api"

/**
 * The most recently built api jar in the sibling checkout, whatever its version.
 *
 * Local development only - CI uses the downloaded jar. Deliberately not a hardcoded file name:
 * this was pinned to boss-plugin-api-1.0.64.jar, the sibling checkout had moved on to 1.0.72, and
 * the whole `ai.rever.boss.plugin.api` package read as "Unresolved reference" on symbols that
 * plainly exist. Newest-by-mtime rather than by version string, because 1.0.9 sorts above 1.0.71
 * lexicographically and the jar you just built is the one you meant.
 */
val localBossPluginApiJar: File? =
    file("$bossPluginApiPath/build/libs")
        .listFiles { f: File -> f.name.startsWith("boss-plugin-api-") && f.name.endsWith(".jar") }
        ?.filterNot { it.name.contains("-sources") || it.name.contains("-thin") }
        ?.maxByOrNull { it.lastModified() }

// Supabase anon key: CI env var > gradle.properties > error
val supabaseAnonKey: String = System.getenv("SUPABASE_ANON_KEY")
    ?: findProperty("SUPABASE_ANON_KEY")?.toString()
    ?: error("SUPABASE_ANON_KEY not set. Add it to gradle.properties or set as environment variable.")

// Admin "delete from store" password hash (SHA-256 hex).
// CI env var > gradle.properties > empty. Empty => password gate disabled (plain confirmation).
val adminDeletePasswordHash: String = System.getenv("ADMIN_DELETE_PASSWORD_HASH")
    ?: findProperty("ADMIN_DELETE_PASSWORD_HASH")?.toString()
    ?: ""

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    if (useLocalDependencies) {
        // Local development: use boss-plugin-api JAR from sibling repo
        // `files { ... }` takes a lazy callable, so the error is raised when the classpath is
        // RESOLVED rather than while the script is configured. Throwing at configuration time
        // made `./gradlew tasks`, `./gradlew clean` and IDE sync all fail for anyone who has not
        // built the sibling checkout - a harsher failure than the unresolved references it
        // replaced. This keeps the useful message and confines it to builds that need the jar.
        compileOnly(
            files({
                localBossPluginApiJar
                    ?: error(
                        "No boss-plugin-api jar in $bossPluginApiPath/build/libs - " +
                            "run ./gradlew jar in the sibling boss-plugin-api checkout first.",
                    )
            }),
        )
    } else {
        // CI: use downloaded JAR
        compileOnly(files("build/downloaded-deps/boss-plugin-api.jar"))
    }

    // Compose dependencies
    implementation(compose.desktop.currentOs)
    implementation(compose.runtime)
    implementation(compose.ui)
    implementation(compose.foundation)
    implementation(compose.material)
    implementation(compose.materialIconsExtended)

    // Decompose for ComponentContext
    implementation("com.arkivanov.decompose:decompose:3.3.0")
    implementation("com.arkivanov.essenty:lifecycle:2.5.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // Serialization (for JSON parsing)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Supabase SDK (provided by host classloader at runtime)
    compileOnly("io.github.jan-tennert.supabase:postgrest-kt:3.6.0")
    compileOnly("io.github.jan-tennert.supabase:realtime-kt:3.6.0")
    compileOnly("io.github.jan-tennert.supabase:functions-kt:3.6.0")
    compileOnly("io.ktor:ktor-client-core:3.4.0")
    compileOnly("io.ktor:ktor-client-cio:3.4.0")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

// Task to build plugin JAR with compiled classes only
tasks.register<Jar>("buildPluginJar") {
    archiveFileName.set("boss-plugin-plugin-manager-${version}.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes(
            "Implementation-Title" to "BOSS Toolbox",
            "Implementation-Version" to version,
            "Main-Class" to "ai.rever.boss.plugin.dynamic.pluginmanager.PluginManagerDynamicPlugin"
        )
    }

    // Include compiled classes
    from(sourceSets.main.get().output)

    // Include plugin manifest
    from("src/main/resources")
}

// Sync version from build.gradle.kts into plugin.json (single source of truth)
tasks.processResources {
    inputs.property("pluginVersion", version)
    filesMatching("**/plugin.json") {
        filter { line ->
            line.replace(Regex(""""version"\s*:\s*"[^"]*""""), """"version": "\$version"""")
        }
    }
}

// Generate BuildConfig.kt with secrets injected at build time
val generateBuildConfig = tasks.register("generateBuildConfig") {
    val outputDir = layout.buildDirectory.dir("generated/buildconfig")
    outputs.dir(outputDir)
    doLast {
        val dir = outputDir.get().asFile.resolve("ai/rever/boss/plugin/dynamic/pluginmanager")
        dir.mkdirs()
        dir.resolve("BuildConfig.kt").writeText("""
            package ai.rever.boss.plugin.dynamic.pluginmanager

            /** Auto-generated at build time. Do not edit. */
            object BuildConfig {
                const val SUPABASE_ANON_KEY = "$supabaseAnonKey"
                const val ADMIN_DELETE_PASSWORD_HASH = "$adminDeletePasswordHash"
            }
        """.trimIndent() + "\n")
    }
}

sourceSets.main {
    kotlin.srcDir(layout.buildDirectory.dir("generated/buildconfig"))
}

tasks.named("compileKotlin") {
    dependsOn(generateBuildConfig)
}

tasks.build {
    dependsOn("buildPluginJar")
}
