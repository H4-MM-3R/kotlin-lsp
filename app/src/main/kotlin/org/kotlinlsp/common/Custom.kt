package org.kotlinlsp.common

import kotlinx.coroutines.Dispatchers

object CustomDispatcher {
    private val cores = Runtime.getRuntime().availableProcessors()
    val cpu = Dispatchers.Default.limitedParallelism((cores * 2/3).coerceAtMost(8))
}

