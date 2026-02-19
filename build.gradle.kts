import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    id("org.jetbrains.compose") version "1.10.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
}

group = "ai.rever.boss.plugin.dynamic"
version = "1.4.26"

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

// Supabase anon key: CI env var > gradle.properties > error
val supabaseAnonKey: String = System.getenv("SUPABASE_ANON_KEY")
    ?: findProperty("SUPABASE_ANON_KEY")?.toString()
    ?: error("SUPABASE_ANON_KEY not set. Add it to gradle.properties or set as environment variable.")

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    if (useLocalDependencies) {
        // Local development: use boss-plugin-api JAR from sibling repo
        compileOnly(files("$bossPluginApiPath/build/libs/boss-plugin-api-1.0.23.jar"))
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
    compileOnly("io.github.jan-tennert.supabase:postgrest-kt:3.3.0")
    compileOnly("io.github.jan-tennert.supabase:realtime-kt:3.3.0")
    compileOnly("io.github.jan-tennert.supabase:functions-kt:3.3.0")
    compileOnly("io.ktor:ktor-client-core:3.4.0")
    compileOnly("io.ktor:ktor-client-cio:3.4.0")
}

// Task to build plugin JAR with compiled classes only
tasks.register<Jar>("buildPluginJar") {
    archiveFileName.set("boss-plugin-plugin-manager-${version}.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes(
            "Implementation-Title" to "BOSS Plugin Manager",
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
