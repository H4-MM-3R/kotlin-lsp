/*
 * Copyright 2026  Kumarapu Hemram
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.kotlinlsp.common

import org.eclipse.lsp4j.DiagnosticSeverity
import org.jetbrains.kotlin.analysis.api.diagnostics.KaSeverity

fun KaSeverity.toLspSeverity(): DiagnosticSeverity =
    when(this) {
        KaSeverity.ERROR -> DiagnosticSeverity.Error
        KaSeverity.WARNING -> DiagnosticSeverity.Warning
        KaSeverity.INFO -> DiagnosticSeverity.Information
    }
