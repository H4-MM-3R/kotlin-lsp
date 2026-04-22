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
import org.kotlinlsp.index.db.Declaration
import org.kotlinlsp.index.db.adapters.prefixSearch

fun Index.getCompletions(prefix: String): Sequence<Declaration> = query {
    it.declarationsDb.prefixSearch<Declaration>(prefix)
        .map { (_, value) -> value }
}
