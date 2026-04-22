/*
 * Copyright 2026  Kumarapu Hemram
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.kotlinlsp.analysis.services

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.impl.VirtualFileEnumeration
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinAnnotationsResolver
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinAnnotationsResolverFactory
import org.jetbrains.kotlin.analysis.api.platform.declarations.KotlinDeclarationProvider
import org.jetbrains.kotlin.analysis.api.platform.declarations.createDeclarationProvider
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.parentOrNull
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.util.collectionUtils.filterIsInstanceAnd
import org.kotlinlsp.common.info
import org.kotlinlsp.common.profile
import org.kotlinlsp.common.warn
import org.kotlinlsp.index.Index

class AnnotationsResolverFactory : KotlinAnnotationsResolverFactory {
    private lateinit var project: Project
    private lateinit var index: Index

    fun setup(project: Project, index: Index) {
        this.project = project
        this.index = index
    }

    override fun createAnnotationResolver(searchScope: GlobalSearchScope): KotlinAnnotationsResolver {
        return AnnotationsResolver(project, searchScope, index)
    }
}

class AnnotationsResolver(
    project: Project,
    private val scope: GlobalSearchScope,
    private val index: Index
) : KotlinAnnotationsResolver {
    private val declarationProvider: DeclarationProvider by lazy {
        project.createDeclarationProvider(scope, contextualModule = null) as DeclarationProvider
    }

    override fun declarationsByAnnotation(annotationClassId: ClassId): Set<KtAnnotated> =
        profile("declarationsByAnnotation", "$annotationClassId") {
            allDeclarations().asSequence()
                .filter { annotationClassId in annotationsOnDeclaration(it) }
                .toSet()
        }

    private fun allDeclarations(): List<KtDeclaration> {
        val virtualFiles = VirtualFileEnumeration.extract(scope)
        if(virtualFiles == null) {
            // It's fine, we are allowed to return false negatives in this service
            return emptyList()
        }
        val filesInScope = virtualFiles.filesIfCollection.orEmpty()
            .asSequence()
            .filter { it in scope }
            .mapNotNull { index.getKtFile(it) }

        val result = mutableListOf<KtDeclaration>()

        val visitor = declarationRecursiveVisitor visit@{
            val isLocal = when (it) {
                is KtClassOrObject -> it.isLocal
                is KtFunction -> it.isLocal
                is KtProperty -> it.isLocal
                else -> return@visit
            }

            if (!isLocal) {
                result += it
            }
        }

        filesInScope.forEach { it.accept(visitor) }

        return result
    }

    override fun annotationsOnDeclaration(declaration: KtAnnotated): Set<ClassId> =
        profile("annotationsOnDeclaration", "$declaration") {
            // This one is straight copied from standalone platform
            declaration.annotationEntries
                .asSequence()
                .flatMap { it.typeReference?.resolveAnnotationClassIds(declarationProvider).orEmpty() }
                .toSet()
        }
}

private fun KtTypeReference.resolveAnnotationClassIds(
    declarationProvider: KotlinDeclarationProvider,
    candidates: MutableSet<ClassId> = mutableSetOf()
): Set<ClassId> {
    val annotationTypeElement = typeElement as? KtUserType
    val referencedName = annotationTypeElement?.referencedFqName() ?: return emptySet()
    if (referencedName.isRoot) return emptySet()

    if (!referencedName.parent().isRoot) {
        // we assume here that the annotation is used by its fully-qualified name
        return buildSet { referencedName.resolveToClassIds(this, declarationProvider) }
    }

    val targetName = referencedName.shortName()
    for (import in containingKtFile.importDirectives) {
        val importedName = import.importedFqName ?: continue
        when {
            import.isAllUnder -> importedName.child(targetName).resolveToClassIds(candidates, declarationProvider)
            importedName.shortName() == targetName -> importedName.resolveToClassIds(candidates, declarationProvider)
        }
    }

    containingKtFile.packageFqName.child(targetName).resolveToClassIds(candidates, declarationProvider)
    return candidates
}

private fun KtUserType.referencedFqName(): FqName? {
    val allTypes = generateSequence(this) { it.qualifier }.toList().asReversed()
    val allQualifiers = allTypes.map { it.referencedName ?: return null }

    return FqName.fromSegments(allQualifiers)
}

private fun FqName.resolveToClassIds(to: MutableSet<ClassId>, declarationProvider: KotlinDeclarationProvider) {
    toClassIdSequence().mapNotNullTo(to) { classId ->
        val classes = declarationProvider.getAllClassesByClassId(classId)
        val typeAliases = declarationProvider.getAllTypeAliasesByClassId(classId)
        typeAliases.singleOrNull()?.getTypeReference()?.resolveAnnotationClassIds(declarationProvider, to)

        val annotations = classes.filterIsInstanceAnd<KtClass> { it.isAnnotation() }
        annotations.singleOrNull()?.let {
            classId
        }
    }
}

private fun FqName.toClassIdSequence(): Sequence<ClassId> {
    var currentName = shortNameOrSpecial()
    if (currentName.isSpecial) return emptySequence()
    var currentParent = parentOrNull() ?: return emptySequence()
    var currentRelativeName = currentName.asString()

    return sequence {
        while (true) {
            yield(ClassId(currentParent, FqName(currentRelativeName), isLocal = false))
            currentName = currentParent.shortNameOrSpecial()
            if (currentName.isSpecial) break
            currentParent = currentParent.parentOrNull() ?: break
            currentRelativeName = "${currentName.asString()}.$currentRelativeName"
        }
    }
}
