/*
 * Copyright 2026  Kumarapu Hemram
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

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
