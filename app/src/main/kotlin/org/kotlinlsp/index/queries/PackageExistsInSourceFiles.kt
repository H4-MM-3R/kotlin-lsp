/*
 * Copyright 2026  Kumarapu Hemram
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.kotlinlsp.index.queries

import org.jetbrains.kotlin.name.FqName
import org.kotlinlsp.index.Index

fun Index.packageExistsInSourceFiles(fqName: FqName): Boolean = query { db ->
    db.packagesDb.prefixSearchRaw(fqName.asString()).iterator().hasNext()
}
