package org.kotlinlsp.buildsystem

import org.kotlinlsp.analysis.modules.Module

interface BuildSystem {
    // List of files used to trigger this build system
    val markerFiles: List<String>

    data class Result(val modules: List<Module>, val metadata: String?)

    // Resolves the modules if the cached metadata is not up to date
    // Returns null if cached metadata is up to date, otherwise
    // it returns the modules along with the current new metadata
    // If the returned metadata is null it means caching is disabled
    fun resolveModulesIfNeeded(cachedMetadata: String?): Result?
}
