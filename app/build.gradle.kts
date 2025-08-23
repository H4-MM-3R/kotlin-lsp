import java.time.Duration

plugins {
    val kotlinVersion = "2.1.20"
    kotlin("jvm") version kotlinVersion
    kotlin("plugin.serialization") version kotlinVersion
    application
}

// Ensure Kotlin compiles to JVM 17 bytecode
kotlin {
    jvmToolchain(17)
}

application {
    applicationName = "kotlin-lsp"
    mainClass = "org.kotlinlsp.MainKt"
    version = "1.8"
}

repositories {
    mavenCentral()

    maven(url = "https://maven.pkg.jetbrains.space/kotlin/p/kotlin/bootstrap")
    maven(url = "https://maven.pkg.jetbrains.space/kotlin/p/kotlin/kotlin-ide-plugin-dependencies")
    maven(url = "https://www.jetbrains.com/intellij-repository/releases")
    maven(url = "https://cache-redirector.jetbrains.com/intellij-third-party-dependencies")
    maven(url = "https://repo.gradle.org/gradle/libs-releases")
}

dependencies {
    val intellijVersion = "241.19416.19"
    implementation("com.jetbrains.intellij.platform:core:$intellijVersion")
    implementation("com.jetbrains.intellij.platform:core-impl:$intellijVersion")
    implementation("com.jetbrains.intellij.platform:util:$intellijVersion")
    // implementation("com.jetbrains.intellij.java:java-decompiler-engine:243.22562.218") // using vineflower instead
    implementation("org.vineflower:vineflower:1.11.1")

    val analysisApiKotlinVersion = "2.2.20-dev-2432" // 13-May-2025 (version that KSP uses)
    implementation("org.jetbrains.kotlin:kotlin-compiler:$analysisApiKotlinVersion")
    implementation("com.github.ben-manes.caffeine:caffeine:2.9.3")  // Needed by kotlin analysis api
    listOf(
        "org.jetbrains.kotlin:analysis-api-k2-for-ide",
        "org.jetbrains.kotlin:analysis-api-for-ide",
        "org.jetbrains.kotlin:low-level-api-fir-for-ide",
        "org.jetbrains.kotlin:analysis-api-platform-interface-for-ide",
        "org.jetbrains.kotlin:symbol-light-classes-for-ide",
        "org.jetbrains.kotlin:analysis-api-impl-base-for-ide",
        "org.jetbrains.kotlin:kotlin-compiler-common-for-ide",
        "org.jetbrains.kotlin:kotlin-compiler-fir-for-ide"
    ).forEach {
        implementation("$it:$analysisApiKotlinVersion") { isTransitive = false }
    }

    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.24.0")
    implementation("org.rocksdb:rocksdbjni:10.0.1")
    implementation("org.gradle:gradle-tooling-api:8.14")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.mockito:mockito-core:5.17.0")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

tasks.test {
    val isCI: String? by project
    if (!isCI.isNullOrBlank()) {
        useJUnitPlatform {
            includeTags("CI")
        }
    } else {
        useJUnitPlatform()
    }

    forkEvery = 1
    maxParallelForks = 1
    timeout = Duration.ofMinutes(8)
    jvmArgs("-XX:+EnableDynamicAgentLoading")

    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
        showStandardStreams = true

        events("passed", "skipped", "failed")
    }
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Version" to project.version
        )
    }
}

distributions {
    main {
        contents {
            into("license") {
                from("../LICENSE")
                from("../NOTICE")
            }
            into("license/thirdparty") {
                from("../licenses")
            }
        }
    }
}
