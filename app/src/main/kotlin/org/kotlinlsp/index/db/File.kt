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

import com.intellij.openapi.project.Project
import kotlinx.serialization.Serializable
import org.jetbrains.kotlin.psi.KtFile
import org.kotlinlsp.common.read
import org.kotlinlsp.index.db.adapters.get
import org.kotlinlsp.index.db.adapters.put
import java.time.Instant

data class File(
    val path: String,
    val packageFqName: String,
    val lastModified: Instant,
    val modificationStamp: Long,
    val indexed: Boolean,
    val declarationKeys: MutableList<String> = mutableListOf()
) {
    companion object {
        fun fromKtFile(ktFile: KtFile, project: Project, indexed: Boolean): File = project.read {
            val packageFqName = ktFile.packageFqName.asString()
            val file = File(
                packageFqName = packageFqName,
                path = ktFile.virtualFile.url,
                lastModified = Instant.ofEpochMilli(ktFile.virtualFile.timeStamp),
                modificationStamp = ktFile.modificationStamp,
                indexed = indexed,
            )
            file
        }

        // Check if the file record has been modified since last time
        // I think the case of overflowing modificationStamp is not worth to be considered as it is 64bit int
        // (a trillion modifications on the same file in the same coding session)
        fun shouldBeSkipped(existingFile: File?, newFile: File) = existingFile != null &&
                !existingFile.lastModified.isBefore(newFile.lastModified) &&
                existingFile.modificationStamp >= newFile.modificationStamp &&
                (newFile.modificationStamp != 0L || existingFile.modificationStamp == 0L)
    }
}

fun File.toDto(): FileDto = FileDto(
    packageFqName = packageFqName,
    lastModified = lastModified.toEpochMilli(),
    modificationStamp = modificationStamp,
    indexed = indexed,
    declarationKeys = declarationKeys
)

@Serializable
data class FileDto(
    val packageFqName: String,
    val lastModified: Long,
    val modificationStamp: Long,
    val indexed: Boolean,
    val declarationKeys: List<String>
)

fun Database.file(path: String): File? {
    return filesDb.get<FileDto>(path)?.let {
        File(
            path = path,
            packageFqName = it.packageFqName,
            lastModified = Instant.ofEpochMilli(it.lastModified),
            modificationStamp = it.modificationStamp,
            indexed = it.indexed,
            declarationKeys = it.declarationKeys.toMutableList()
        )
    }
}

fun Database.setFile(file: File) {
    val dto = file.toDto()
    val previousPackageFqName = filesDb.get<FileDto>(file.path)?.packageFqName

    filesDb.put(file.path, dto)

    updatePackageFiles(file.path, previousPackageFqName, file.packageFqName)
}
