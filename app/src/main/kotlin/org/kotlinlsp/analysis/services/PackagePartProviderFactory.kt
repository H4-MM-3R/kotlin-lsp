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

import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.analysis.api.platform.packages.KotlinPackagePartProviderFactory
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.JvmPackagePartProvider
import org.jetbrains.kotlin.cli.jvm.index.JavaRoot
import org.jetbrains.kotlin.load.kotlin.PackagePartProvider
import org.kotlinlsp.common.trace

class PackagePartProviderFactory: KotlinPackagePartProviderFactory {
    private lateinit var allLibraryRoots: List<JavaRoot>

    fun setup(allLibraryRoots: List<JavaRoot>) {
        this.allLibraryRoots = allLibraryRoots
    }

    override fun createPackagePartProvider(scope: GlobalSearchScope): PackagePartProvider {
        trace("createPackagePartProvider $scope")

        return JvmPackagePartProvider(latestLanguageVersionSettings, scope).apply {
            addRoots(allLibraryRoots, MessageCollector.NONE)
        }
    }
}
