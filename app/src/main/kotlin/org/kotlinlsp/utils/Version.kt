package org.kotlinlsp.utils

fun getLspVersion(): String {
    val pkg = object {}.javaClass.`package`
    return pkg.implementationVersion ?: "unknown"
}