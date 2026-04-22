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

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

private val key = Key.create<ReentrantReadWriteLock>("org.kotlinlsp.rwlock")
private val lock = ReentrantReadWriteLock()

fun Project.registerRWLock() {
    putUserData(key, lock)
}

fun <T> Project.read(fn: () -> T): T {
    val lock = getUserData(key)!!
    return lock.read(fn)
}

fun <T> Project.write(fn: () -> T): T {
    val lock = getUserData(key)!!
    return lock.write(fn)
}
