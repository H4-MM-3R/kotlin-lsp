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

import com.intellij.mock.MockProject
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.analysis.api.platform.mergeSpecificProviders
import org.jetbrains.kotlin.analysis.api.platform.packages.*
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.kotlinlsp.common.profile
import org.kotlinlsp.index.Index
import org.kotlinlsp.index.queries.packageExistsInSourceFiles
import org.kotlinlsp.index.queries.subpackageNames

class PackageProviderFactory: KotlinPackageProviderFactory {
    private lateinit var project: MockProject
    private lateinit var index: Index

    fun setup(project: MockProject, index: Index) {
        this.project = project
        this.index = index
    }

    override fun createPackageProvider(searchScope: GlobalSearchScope): KotlinPackageProvider = PackageProvider(project, searchScope, index)
}

private class PackageProvider(
    project: Project,
    searchScope: GlobalSearchScope,
    private val index: Index
): KotlinPackageProviderBase(project, searchScope) {
    override fun doesKotlinOnlyPackageExist(packageFqName: FqName): Boolean = profile("doesKotlinOnlyPackageExist", "$packageFqName") {
        packageFqName.isRoot || index.packageExistsInSourceFiles(packageFqName)
    }

    override fun getKotlinOnlySubpackageNames(packageFqName: FqName): Set<Name> = profile("getKotlinOnlySubpackageNames", "$packageFqName") {
        index.subpackageNames(packageFqName.asString()).map { Name.identifier(it) }.toSet()
    }
}

class PackageProviderMerger(private val project: Project) : KotlinPackageProviderMerger {
    override fun merge(providers: List<KotlinPackageProvider>): KotlinPackageProvider =
        providers.mergeSpecificProviders<_, PackageProvider>(KotlinCompositePackageProvider.factory) { targetProviders ->
            val combinedScope = GlobalSearchScope.union(targetProviders.map { it.searchScope })
            project.createPackageProvider(combinedScope).apply {
                check(this is PackageProvider) {
                    "`${PackageProvider::class.simpleName}` can only be merged into a combined package provider of the same type."
                }
            }
        }
}
