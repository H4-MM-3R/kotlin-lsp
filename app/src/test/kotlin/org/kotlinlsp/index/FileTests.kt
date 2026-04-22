/*
 * Copyright 2026  Kumarapu Hemram
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.kotlinlsp.index

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.kotlinlsp.index.db.File
import java.time.Instant
import java.util.stream.Stream

class FileTests {
    companion object {
        @JvmStatic
        fun provideData(): Stream<Arguments> = Stream.of(
            // Unmodified file
            Arguments.of(
                buildFile(modificationStamp = 0, lastModified = Instant.ofEpochMilli(100)),
                buildFile(modificationStamp = 0, lastModified = Instant.ofEpochMilli(100)),
                true
            ),
            // File modified on disk
            Arguments.of(
                buildFile(modificationStamp = 0, lastModified = Instant.ofEpochMilli(50)),
                buildFile(modificationStamp = 0, lastModified = Instant.ofEpochMilli(100)),
                false
            ),
            // File modified in memory
            Arguments.of(
                buildFile(modificationStamp = 0, lastModified = Instant.ofEpochMilli(50)),
                buildFile(modificationStamp = 1, lastModified = Instant.ofEpochMilli(100)),
                false
            ),
            Arguments.of(
                buildFile(modificationStamp = 1, lastModified = Instant.ofEpochMilli(50)),
                buildFile(modificationStamp = 2, lastModified = Instant.ofEpochMilli(100)),
                false
            ),
            // Reloading file from disk (after being edited in memory but not saved)
            Arguments.of(
                buildFile(modificationStamp = 10, lastModified = Instant.ofEpochMilli(100)),
                buildFile(modificationStamp = 0, lastModified = Instant.ofEpochMilli(100)),
                false
            ),
            // Old in memory file trying to be indexed
            Arguments.of(
                buildFile(modificationStamp = 10, lastModified = Instant.ofEpochMilli(50)),
                buildFile(modificationStamp = 9, lastModified = Instant.ofEpochMilli(50)),
                true
            ),
            // New file should always be indexed
            Arguments.of(
                null,
                buildFile(modificationStamp = 9, lastModified = Instant.ofEpochMilli(50)),
                false
            ),
        )
    }

    @ParameterizedTest
    @MethodSource("provideData")
    fun `test file index skip logic`(existingFile: File?, newFile: File, result: Boolean) {
        assertEquals(File.shouldBeSkipped(existingFile = existingFile, newFile = newFile), result)
    }

}

private fun buildFile(lastModified: Instant, modificationStamp: Long, indexed: Boolean = false): File {
    val path = "/sample/path.kt"
    val packageFqName = "com.example"
    return File(
        path = path,
        modificationStamp = modificationStamp,
        packageFqName = packageFqName,
        lastModified = lastModified,
        indexed = indexed
    )
}