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

import org.kotlinlsp.index.Index

fun Index.subpackageNames(fqName: String): Set<String> = query { db ->
    db.packagesDb.prefixSearchRaw(fqName)
        .filter { (key, _) -> key != fqName }
        .map { (key, _) -> key.removePrefix("$fqName.").split(".") }
        .filter { it.isNotEmpty() }
        .map { it.first() }
        .toSet()
}
