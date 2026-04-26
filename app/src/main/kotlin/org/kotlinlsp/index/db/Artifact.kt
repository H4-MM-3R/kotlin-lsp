package org.kotlinlsp.index.db

import kotlinx.serialization.Serializable
import org.kotlinlsp.index.db.adapters.get
import org.kotlinlsp.index.db.adapters.put
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.absolutePathString

const val ARTIFACT_KIND_BINARY = "binary"
const val ARTIFACT_KIND_JDK_BINARY = "jdk-binary"
const val ARTIFACT_KIND_JDK_SOURCE = "jdk-source"
const val ARTIFACT_KIND_SOURCE = "source"

data class Artifact(
    val path: String,
    val kind: String,
    val lastModified: Long,
    val sizeBytes: Long,
    val schemaVersion: Int,
    val indexed: Boolean,
    val indexedAt: Long
) {
    fun key(): String = key(path, kind)

    companion object {
        fun key(path: String, kind: String): String = "$kind::$path"

        fun fromPath(path: Path, kind: String, indexed: Boolean): Artifact? {
            if (!Files.exists(path)) return null

            val lastModified = try {
                Files.getLastModifiedTime(path).toMillis()
            } catch (_: Exception) {
                return null
            }

            val sizeBytes = try {
                if (Files.isRegularFile(path)) Files.size(path) else 0L
            } catch (_: Exception) {
                return null
            }

            return Artifact(
                path = path.absolutePathString(),
                kind = kind,
                lastModified = lastModified,
                sizeBytes = sizeBytes,
                schemaVersion = CURRENT_SCHEMA_VERSION,
                indexed = indexed,
                indexedAt = Instant.now().toEpochMilli()
            )
        }

        fun shouldBeSkipped(existing: Artifact?, current: Artifact?): Boolean {
            if (existing == null || current == null) return false
            if (!existing.indexed) return false
            if (existing.schemaVersion != CURRENT_SCHEMA_VERSION) return false
            if (existing.path != current.path) return false
            if (existing.kind != current.kind) return false
            if (existing.lastModified != current.lastModified) return false
            if (existing.sizeBytes != current.sizeBytes) return false
            return true
        }
    }
}

@Serializable
data class ArtifactDto(
    val kind: String,
    val lastModified: Long,
    val sizeBytes: Long,
    val schemaVersion: Int,
    val indexed: Boolean,
    val indexedAt: Long
)

fun Artifact.toDto(): ArtifactDto = ArtifactDto(
    kind = kind,
    lastModified = lastModified,
    sizeBytes = sizeBytes,
    schemaVersion = schemaVersion,
    indexed = indexed,
    indexedAt = indexedAt
)

fun Database.artifact(path: String, kind: String): Artifact? {
    return artifactsDb.get<ArtifactDto>(Artifact.key(path, kind))?.let {
        Artifact(
            path = path,
            kind = it.kind,
            lastModified = it.lastModified,
            sizeBytes = it.sizeBytes,
            schemaVersion = it.schemaVersion,
            indexed = it.indexed,
            indexedAt = it.indexedAt
        )
    }
}

fun Database.setArtifact(artifact: Artifact) {
    artifactsDb.put(artifact.key(), artifact.toDto())
}