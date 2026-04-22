/*
 * Copyright 2026  Kumarapu Hemram
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.kotlinlsp.index.worker

import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.psi.KtFile
import org.kotlinlsp.index.db.Database
import org.kotlinlsp.index.db.File
import org.kotlinlsp.index.db.file
import org.kotlinlsp.index.db.setFile

fun scanKtFile(project: Project, ktFile: KtFile, db: Database) {
    val newFile = File.fromKtFile(ktFile, project, indexed = false)

    val existingFile = db.file(newFile.path)
    if (
        File.shouldBeSkipped(existingFile = existingFile, newFile = newFile)
    ) return

    // Update the file timestamp and package
    db.setFile(newFile)
}
