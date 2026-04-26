/*
 * Copyright 2026  Kumarapu Hemram
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.kotlinlsp.index.db

import org.kotlinlsp.common.getCachePath
import org.kotlinlsp.common.info
import org.kotlinlsp.common.warn
import org.kotlinlsp.index.db.adapters.DatabaseAdapter
import org.kotlinlsp.index.db.adapters.RocksDBAdapter
import org.kotlinlsp.index.db.adapters.get
import org.kotlinlsp.index.db.adapters.put
import org.rocksdb.RocksDBException
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.io.path.absolutePathString
import kotlin.concurrent.withLock

const val CURRENT_SCHEMA_VERSION = 5    // Increment on schema changes
const val VERSION_KEY = "__version"

class Database(rootFolder: String) {
    private val cachePath = getCachePath(rootFolder)
    private val packageListLock = ReentrantLock()
    val artifactsDb: DatabaseAdapter
    val filesDb: DatabaseAdapter
    val packagesDb: DatabaseAdapter
    val declarationsDb: DatabaseAdapter
    val sourcesDb: DatabaseAdapter
    val sourceFilesDb: DatabaseAdapter

    init {
        try {
            var projectDb = RocksDBAdapter(cachePath.resolve("project"))
            val schemaVersion = projectDb.get<Int>(VERSION_KEY)

            if(schemaVersion == null || schemaVersion != CURRENT_SCHEMA_VERSION) {
                // Schema version mismatch, wipe the db
                info("Index DB schema version mismatch, recreating!")
                projectDb.close()
                deleteAll()

                projectDb = RocksDBAdapter(cachePath.resolve("project"))
                projectDb.put(VERSION_KEY, CURRENT_SCHEMA_VERSION)
            }

            artifactsDb = RocksDBAdapter(cachePath.resolve("artifacts"))
            filesDb = RocksDBAdapter(cachePath.resolve("files"))
            packagesDb = RocksDBAdapter(cachePath.resolve("packages"))
            declarationsDb = RocksDBAdapter(cachePath.resolve("declarations"))
            sourcesDb = RocksDBAdapter(cachePath.resolve("sources"))
            sourceFilesDb = RocksDBAdapter(cachePath.resolve("sourceFiles"))
            projectDb.close()
        } catch (e: RocksDBException) {
            warn("Failed to initialize database: ${e.message}")
            warn("This might be due to another Kotlin LSP instance running. Please ensure only one instance is active.")
            throw e
        } catch (e: Exception) {
            warn("Unexpected error during database initialization: ${e.message}")
            throw e
        }
    }

    fun close() {
        artifactsDb.close()
        filesDb.close()
        packagesDb.close()
        declarationsDb.close()
        sourcesDb.close()
        sourceFilesDb.close()
    }

    fun addSourceFile(packageFqName: String, fileUrl: String): Boolean = packageListLock.withLock {
        val existing = sourcesDb.get<List<String>>(packageFqName)?.toMutableList() ?: mutableListOf()
        if (existing.contains(fileUrl)) {
            return@withLock false
        }

        existing.add(fileUrl)
        sourcesDb.put(packageFqName, existing)
        true
    }

    fun updatePackageFiles(filePath: String, previousPackageFqName: String?, packageFqName: String) {
        packageListLock.withLock {
            if (previousPackageFqName == packageFqName) return

            if (previousPackageFqName != null) {
                val previousFiles = packagesDb.get<List<String>>(previousPackageFqName)?.toMutableList() ?: mutableListOf()
                previousFiles.remove(filePath)
                if (previousFiles.isEmpty()) {
                    packagesDb.remove(previousPackageFqName)
                } else {
                    packagesDb.put(previousPackageFqName, previousFiles)
                }
            }

            val files = packagesDb.get<List<String>>(packageFqName)?.toMutableList() ?: mutableListOf()
            if (!files.contains(filePath)) {
                files.add(filePath)
                packagesDb.put(packageFqName, files)
            }
        }
    }

    private fun deleteAll() {
        File(cachePath.resolve("project").absolutePathString()).deleteRecursively()
        File(cachePath.resolve("artifacts").absolutePathString()).deleteRecursively()
        File(cachePath.resolve("files").absolutePathString()).deleteRecursively()
        File(cachePath.resolve("packages").absolutePathString()).deleteRecursively()
        File(cachePath.resolve("declarations").absolutePathString()).deleteRecursively()
        File(cachePath.resolve("sources").absolutePathString()).deleteRecursively()
        File(cachePath.resolve("sourceFiles").absolutePathString()).deleteRecursively()
    }
}
