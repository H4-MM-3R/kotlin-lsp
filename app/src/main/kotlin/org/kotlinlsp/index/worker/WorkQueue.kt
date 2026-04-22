/*
 * Copyright 2026  Kumarapu Hemram
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.kotlinlsp.index.worker

import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class WorkQueue<T> {
    private val scanQueue: Queue<T> = LinkedList()
    private val editQueue: Stack<T> = Stack()
    private val indexQueue: Queue<T> = LinkedList()
    private val mutex = ReentrantLock()
    private val notEmpty = mutex.newCondition()
    private val notFullEdit = mutex.newCondition()
    private val notFullIndex = mutex.newCondition()
    private val notFullScan = mutex.newCondition()

    companion object {
        const val MAX_EDIT_SIZE = 20
        const val MAX_INDEX_SIZE = 100
        const val MAX_SCAN_SIZE = 100
    }

    fun putEditQueue(item: T) {
        mutex.withLock {
            while (editQueue.size >= MAX_EDIT_SIZE) {
                notFullEdit.await()
            }
            editQueue.push(item)
            notEmpty.signal()
        }
    }

    fun putIndexQueue(item: T) {
        mutex.withLock {
            while (indexQueue.size >= MAX_INDEX_SIZE) {
                notFullIndex.await()
            }
            indexQueue.offer(item)
            notEmpty.signal()
        }
    }

    fun putScanQueue(item: T) {
        mutex.withLock {
            while (scanQueue.size >= MAX_SCAN_SIZE) {
                notFullScan.await()
            }
            scanQueue.offer(item)
            notEmpty.signal()
        }
    }

    fun take(): T = mutex.withLock {
        while (editQueue.isEmpty() && indexQueue.isEmpty() && scanQueue.isEmpty()) {
            notEmpty.await()
        }

        // Priority is ScanQueue > EditQueue > IndexQueue
        if (scanQueue.isNotEmpty()) {
            val item = scanQueue.poll()
            notFullScan.signal()
            return@withLock item
        }

        if (editQueue.isNotEmpty()) {
            val item = editQueue.pop()
            notFullEdit.signal()
            return@withLock item
        }

        val item = indexQueue.poll()
        notFullIndex.signal()
        return@withLock item
    }
}
