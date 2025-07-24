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
import kotlin.io.path.absolutePathString

const val CURRENT_SCHEMA_VERSION = 5    // Increment on schema changes
const val VERSION_KEY = "__version"

class Database(rootFolder: String) {
    private val cachePath = getCachePath(rootFolder)
    val filesDb: DatabaseAdapter
    val packagesDb: DatabaseAdapter
    val declarationsDb: DatabaseAdapter

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

            filesDb = RocksDBAdapter(cachePath.resolve("files"))
            packagesDb = RocksDBAdapter(cachePath.resolve("packages"))
            declarationsDb = RocksDBAdapter(cachePath.resolve("declarations"))
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
        filesDb.close()
        packagesDb.close()
        declarationsDb.close()
    }

    private fun deleteAll() {
        File(cachePath.resolve("project").absolutePathString()).deleteRecursively()
        File(cachePath.resolve("files").absolutePathString()).deleteRecursively()
        File(cachePath.resolve("packages").absolutePathString()).deleteRecursively()
        File(cachePath.resolve("declarations").absolutePathString()).deleteRecursively()
    }
}
