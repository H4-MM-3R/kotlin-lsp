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
import org.jetbrains.kotlin.analysis.api.platform.modification.KotlinModificationTrackerByEventFactoryBase

// TODO May need implementation for module changes (e.g. adding a file)
class ModificationTrackerFactory(project: Project) : KotlinModificationTrackerByEventFactoryBase(project)