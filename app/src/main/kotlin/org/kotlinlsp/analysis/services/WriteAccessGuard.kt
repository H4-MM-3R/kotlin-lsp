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

import com.intellij.openapi.editor.impl.DocumentWriteAccessGuard
import com.intellij.openapi.editor.Document

class WriteAccessGuard: DocumentWriteAccessGuard() {
    override fun isWritable(p0: Document): Result {
        return success()
    }
}
