package org.kotlinlsp.index.db.adapters

import org.rocksdb.*
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import org.kotlinlsp.common.warn
import org.kotlinlsp.common.info

class RocksDBAdapter(private val path: Path): DatabaseAdapter {
    companion object {
        init {
            RocksDB.loadLibrary()
        }

        val options = Options().apply {
            setCreateIfMissing(true)
            setKeepLogFileNum(1)
            setInfoLogLevel(InfoLogLevel.FATAL_LEVEL)
        }
    }

    private val db = initializeDatabase()

    private fun initializeDatabase(): RocksDB {
        var attempts = 0
        val maxAttempts = 3
        val retryDelayMs = 1000L

        while (attempts < maxAttempts) {
            try {
                return RocksDB.open(options, path.absolutePathString())
            } catch (e: RocksDBException) {
                attempts++
                if (e.message?.contains("Resource temporarily unavailable") == true || 
                    e.message?.contains("LOCK") == true) {
                    warn("RocksDB lock conflict detected (attempt $attempts/$maxAttempts): ${e.message}")
                    
                    if (attempts < maxAttempts) {
                        // Try to clean up any stale lock files
                        cleanupStaleLocks()
                        Thread.sleep(retryDelayMs * attempts) // Exponential backoff
                    } else {
                        warn("Failed to acquire RocksDB lock after $maxAttempts attempts. Cleaning up and retrying once more...")
                        cleanupStaleLocks()
                        // Final attempt after cleanup
                        return RocksDB.open(options, path.absolutePathString())
                    }
                } else {
                    throw e // Re-throw if it's not a lock-related error
                }
            }
        }
        
        throw RocksDBException("Failed to initialize RocksDB after $maxAttempts attempts")
    }

    private fun cleanupStaleLocks() {
        try {
            val lockFile = File(path.absolutePathString(), "LOCK")
            if (lockFile.exists()) {
                info("Removing stale RocksDB lock file: ${lockFile.absolutePath}")
                lockFile.delete()
            }
        } catch (e: Exception) {
            warn("Failed to cleanup stale lock file: ${e.message}")
        }
    }

    override fun putRawData(key: String, value: ByteArray) {
        db.put(key.toByteArray(), value)
    }

    override fun putRawData(values: Iterable<Pair<String, ByteArray>>) {
        val batch = WriteBatch()
        values.forEach { (key, value) ->
            batch.put(key.toByteArray(), value)
        }
        db.write(WriteOptions(), batch)
        batch.close()
    }

    override fun getRawData(key: String): ByteArray? {
        val data = db.get(key.toByteArray()) ?: return null
        return data
    }

    override fun prefixSearchRaw(prefix: String): Sequence<Pair<String, ByteArray>> = sequence {
        val readOptions = ReadOptions().setPrefixSameAsStart(true)

        readOptions.use {
            val iterator = db.newIterator(readOptions)
            iterator.seek(prefix.toByteArray())

            while (iterator.isValid) {
                val key = iterator.key()
                val keyString = key.toString(Charset.defaultCharset())
                if (!keyString.startsWith(prefix)) break

                yield(Pair(keyString, iterator.value()))
                iterator.next()
            }
        }
    }

    override fun remove(key: String) {
        db.delete(key.toByteArray())
    }

    override fun remove(keys: Iterable<String>) {
        val batch = WriteBatch()
        keys.forEach {
            batch.delete(it.toByteArray())
        }
        db.write(WriteOptions(), batch)
        batch.close()
    }

    override fun close() {
        db.close()
    }

    override fun deleteDb() {
        if(!db.isClosed) db.close()
        File(path.absolutePathString()).deleteRecursively()
    }
}
