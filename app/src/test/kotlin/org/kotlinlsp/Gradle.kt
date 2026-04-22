/*
 * Copyright 2026  Kumarapu Hemram
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.kotlinlsp

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreApplicationEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreApplicationEnvironmentMode
import org.jetbrains.kotlin.cli.jvm.compiler.setupIdeaStandaloneExecution
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.kotlinlsp.analysis.ProgressNotifier
import org.kotlinlsp.buildsystem.GradleBuildSystem
import org.mockito.Mockito.mock
import java.nio.file.Paths
import org.kotlinlsp.analysis.modules.Module
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString

class Gradle {
    private val cwd = Paths.get("").toAbsolutePath().toString()

    private fun scenario(projectName: String, testCase: (GradleBuildSystem) -> Unit) {
        setupIdeaStandaloneExecution()
        val rootFolder = "$cwd/test-projects/$projectName"
        val project = mock(Project::class.java)
        val progressNotifier = mock(ProgressNotifier::class.java)
        val disposable = Disposer.newDisposable()
        val appEnvironment = KotlinCoreApplicationEnvironment.create(disposable, KotlinCoreApplicationEnvironmentMode.UnitTest)
        val buildSystem = GradleBuildSystem(
            project = project,
            progressNotifier = progressNotifier,
            rootFolder = rootFolder,
            appEnvironment = appEnvironment
        )

        try {
            testCase(buildSystem)
        } finally {
            disposable.dispose()
        }
    }

    private fun Module.firstContentRootFilename() = contentRoots.first().fileName.toString()

    @Test
    fun `loads single module project successfully`() = scenario("single-module") { buildSystem ->
        // Act
        val (modules, _) = buildSystem.resolveModulesIfNeeded(cachedMetadata = null)!!

        // Assert
        assertEquals(modules.size, 2)
        assertEquals(modules[0].isSourceModule, true)
        assertEquals(
            modules[0].contentRoots,
            listOf(
                Path("$cwd/test-projects/single-module/app/src/main/java"),
                Path("$cwd/test-projects/single-module/app/src/main/kotlin"),
            )
        )
        assertEquals(modules[0].dependencies.size, 3)
        modules[0].dependencies.forEach {
            assertEquals(it.dependencies.size, 0)
            assertEquals(it.contentRoots.size, 1)
        }
        val depNames = modules[0].dependencies.map { it.firstContentRootFilename() }.toSet()
        assertTrue("annotations-13.0.jar" in depNames)
        assertTrue("kotlin-stdlib-2.1.20.jar" in depNames)

        assertEquals(modules[1].isSourceModule, true)
        assertEquals(
            modules[1].contentRoots,
            listOf(
                Path("$cwd/test-projects/single-module/app/src/test/java"),
                Path("$cwd/test-projects/single-module/app/src/test/kotlin"),
            )
        )
        assertEquals(modules[1].dependencies.size, 4)
        assertEquals(modules[1].dependencies.filter { it.isSourceModule }.size, 1)
    }

    @Test
    fun `loads multi module project successfully`() = scenario("multi-module") { buildSystem ->
        // Act
        val (modules, _) = buildSystem.resolveModulesIfNeeded(cachedMetadata = null)!!

        // Assert
        assertEquals(modules.size, 4)
        assertEquals(modules[0].isSourceModule, true)
        assertEquals(
            modules[0].contentRoots,
            listOf(
                Path("$cwd/test-projects/multi-module/app/src/main/java"),
                Path("$cwd/test-projects/multi-module/app/src/main/kotlin"),
            )
        )
        assertEquals(modules[0].dependencies.size, 4)
        val depNames = modules[0].dependencies.map { it.id }.toSet()
        assertTrue("submodule" in depNames)

        assertEquals(modules[1].isSourceModule, true)
        assertEquals(
            modules[1].contentRoots,
            listOf(
                Path("$cwd/test-projects/multi-module/app/src/test/java"),
                Path("$cwd/test-projects/multi-module/app/src/test/kotlin"),
            )
        )
        assertEquals(modules[1].dependencies.size, 5)
        assertEquals(modules[1].dependencies.filter { it.isSourceModule }.size, 2)

        assertEquals(modules[2].isSourceModule, true)
        assertEquals(
            modules[2].contentRoots,
            listOf(
                Path("$cwd/test-projects/multi-module/submodule/src/main/java"),
                Path("$cwd/test-projects/multi-module/submodule/src/main/kotlin"),
            )
        )
        assertEquals(modules[2].dependencies.size, 3)

        assertEquals(modules[3].isSourceModule, true)
        assertEquals(
            modules[3].contentRoots,
            listOf(
                Path("$cwd/test-projects/multi-module/submodule/src/test/java"),
                Path("$cwd/test-projects/multi-module/submodule/src/test/kotlin"),
            )
        )
        assertEquals(modules[3].dependencies.size, 4)
        assertEquals(modules[3].dependencies.filter { it.isSourceModule }.size, 1)
    }

    @Test
    fun `loads android project successfully`() = scenario("android") { buildSystem ->
        // Act
        val (modules, _) = buildSystem.resolveModulesIfNeeded(cachedMetadata = null)!!

        // Assert
        assertEquals(modules.size, 1)
        assertEquals(modules[0].isSourceModule, true)
        assertTrue(modules[0].dependencies.isNotEmpty())
        assertEquals(
            modules[0].contentRoots,
            listOf(
                Path("$cwd/test-projects/android/app/src/main/kotlin"),
                Path("$cwd/test-projects/android/app/src/main/java"),
                Path("$cwd/test-projects/android/app/src/debug/kotlin"),
                Path("$cwd/test-projects/android/app/src/debug/java"),
            )
        )
    }
}
