import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    id("org.jetbrains.compose") version "1.10.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
}

group = "ai.rever.boss.plugin.dynamic"
version = "1.4.3"

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

// Flag to switch between local development and CI dependencies
// Auto-detect CI environment (GitHub Actions sets CI=true)
val useLocalDependencies = System.getenv("CI") != "true"
val bossConsolePath = "../../BossConsole"

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    if (useLocalDependencies) {
        // Local development: use JARs built from BossConsole repo
        // compileOnly because at runtime, classes come from shared packages (parent classloader)
        compileOnly(fileTree("$bossConsolePath/plugins/plugin-api/build/libs") { include("plugin-api-desktop-*.jar") })
        compileOnly(fileTree("$bossConsolePath/plugins/plugin-ui-core/build/libs") { include("plugin-ui-core-desktop-*.jar") })
    } else {
        // CI: use JARs copied by workflow from BossConsole build
        // compileOnly because at runtime, classes come from shared packages (parent classloader)
        compileOnly(fileTree("build/downloaded-deps") { include("plugin-api-desktop-*.jar") })
        compileOnly(fileTree("build/downloaded-deps") { include("plugin-ui-core-desktop-*.jar") })
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

tasks.build {
    dependsOn("buildPluginJar")
}
