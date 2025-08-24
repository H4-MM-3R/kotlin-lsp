package org.kotlinlsp.index.queries

import org.jetbrains.kotlin.name.FqName
import org.kotlinlsp.index.Index
import org.kotlinlsp.index.db.adapters.get

fun Index.filesForPackage(fqName: FqName): List<String> = query { db ->
    db.packagesDb.get<List<String>>(fqName.asString()) ?: emptyList()
}

fun Index.sourcesForPackage(fqName: FqName): List<String> = query { db ->
    db.sourcesDb.get<List<String>>(fqName.asString()) ?: emptyList()
}