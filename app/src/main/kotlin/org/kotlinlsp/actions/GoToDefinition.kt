/*
 * Copyright 2026  Kumarapu Hemram
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.kotlinlsp.actions

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.ResolveResult
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.psi.KtFile
import org.kotlinlsp.index.Index
import org.jetbrains.kotlin.psi.psiUtil.textRangeWithoutComments
import org.kotlinlsp.common.getCachePath
import org.kotlinlsp.common.findSourceSymbols
import org.kotlinlsp.common.toLspRange
import org.kotlinlsp.common.toOffset
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.kotlinlsp.common.normalizeUri


fun KtReference.mutlipleResolve(): List<PsiElement?> {
    val resolveResults: Array<ResolveResult> = this.multiResolve(false)
    val psiResults = mutableListOf<PsiElement?>()
    resolveResults.forEach { resolveResult -> resolveResult.element?.let { psiResults.add(it) }}
    return psiResults
}

fun goToDefinitionAction(ktFile: KtFile, position: Position, index: Index): List<Location?>? = analyze(ktFile) {
    val offset = position.toOffset(ktFile)
    val ref = ktFile.findReferenceAt(offset) as? KtReference ?: return null
    val elements = ref.mutlipleResolve()

    val isFromSource = elements.map { it?.containingFile }.distinct().any { ele -> ele?.viewProvider?.document != null }

    // Source Jar Decompilation Logic
    if(!isFromSource) {
        val possibleLocations = getFromSourcesJar(ktFile, ref, index)
        if (possibleLocations.isNotEmpty()) return possibleLocations
    }

    if (elements.isEmpty()) {
        return emptyList()
    }

    // Regular source file logic
    val locations = mutableListOf<Location?>()
    
    for (element in elements) {
        val file = element?.containingFile ?: continue
            val range = element.textRange.toLspRange(file)

            locations.add(Location().apply {
                uri = file.virtualFile.url.normalizeUri()
                setRange(range)
            })
    }
    
    return locations
}

private fun KaSession.getFromSourcesJar(ktFile: KtFile, ref: KtReference, index: Index): List<Location> {
    val possibleVal = findSourceSymbols(ktFile, ref, index)
    if (possibleVal.isEmpty()) return emptyList()

    val targetElement = possibleVal.first()
    val cachedFile = cacheSourcesJarFile(targetElement.containingFile, index)

    return listOf(Location().apply {
        uri = cachedFile.toURI().toString().normalizeUri()
        range = targetElement.textRangeWithoutComments.toLspRange(targetElement.containingFile)
    })
}

private fun cacheSourcesJarFile(referenceFile: PsiFile, index: Index): File {
    val packagePath = sourcePackagePath(referenceFile)
    val cacheDir = getCachePath(index.rootFolder)
        .resolve("decompiled")
        .let { baseDir -> if (packagePath == Path.of("")) baseDir else baseDir.resolve(packagePath) }

    Files.createDirectories(cacheDir)

    val cachedFile = cacheDir.resolve(referenceFile.name).toFile()
    if (!cachedFile.exists()) {
        cachedFile.writeBytes(referenceFile.virtualFile.contentsToByteArray())
    }

    return cachedFile
}

private fun sourcePackagePath(referenceFile: PsiFile): Path {
    val packageName = when (referenceFile) {
        is KtFile -> referenceFile.packageFqName.asString()
        is PsiJavaFile -> referenceFile.packageName
        else -> ""
    }

    if (packageName.isBlank()) return Path.of("")

    val packageSegments = packageName.split('.').filter { it.isNotBlank() }.toTypedArray()
    return Path.of("", *packageSegments)
}