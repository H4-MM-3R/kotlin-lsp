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

import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.services.LanguageClient
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.management.ManagementFactory
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

private enum class LogLevel(level: Int) {
    Trace(0),
    Debug(1),
    Info(2),
    Warning(3),
    Error(4),
    Off(5)
}

private fun LogLevel.asMessageType(): MessageType {
    return when (this) {
        LogLevel.Trace -> MessageType.Log
        LogLevel.Debug -> MessageType.Log
        LogLevel.Info -> MessageType.Info
        LogLevel.Warning -> MessageType.Warning
        LogLevel.Error -> MessageType.Error
        LogLevel.Off -> throw IllegalArgumentException("LogLevel Off cannot be converted to MessageType")
    }
}

// Configure as needed
private val logLevel = LogLevel.Debug
private const val profileEnabled = true

private lateinit var logger: LSPLogger
private val profileInfo = mutableMapOf<String, Pair<Int, Duration>>()

private class LSPLogger(val client: LanguageClient) {
    fun log(level: LogLevel, message: String) {
        if (level < logLevel) return

        client.logMessage(MessageParams(level.asMessageType(), message))
    }
}

fun setupLogger(client: LanguageClient) {
    logger = LSPLogger(client)

    // This is to log the exceptions from JUL (java.util.log)
    Logger.getLogger("").addHandler(JULRedirector())
}

fun <T> profile(tag: String, message: String, fn: () -> T): T {
    trace("$tag $message")

    if(profileEnabled) {
        var result: T
        val time = measureTime {
            result = fn()
        }
        if(!profileInfo.containsKey(tag)) {
            profileInfo[tag] = Pair(1, time)
        } else {
            val value = profileInfo[tag]!!
            profileInfo[tag] = Pair(value.first + 1, value.second.plus(time))
        }
        return result
    } else {
        return fn()
    }
}

fun profileJvmStartup() {
    val runtimeMXBean = ManagementFactory.getRuntimeMXBean()
    val jvmStartTimeMillis = runtimeMXBean.startTime
    val deltaMillis = System.currentTimeMillis() - jvmStartTimeMillis
    profileInfo["JVM Startup"] = Pair(1, deltaMillis.milliseconds)
}

fun logProfileInfo() {
    if (!profileEnabled) return

    val sb = StringBuilder()
    sb.appendLine("------------")
    sb.appendLine("PROFILE INFO")
    var totalDuration = Duration.ZERO
    profileInfo.entries.sortedByDescending { it.value.second }.forEach {
        val header = "${it.key} (x${it.value.first}):".padEnd(65)
        val formattedDuration = formatDuration(it.value.second)
        totalDuration += it.value.second
        sb.appendLine("$header $formattedDuration")
    }
    sb.appendLine("------------")
    sb.appendLine("TOTAL: ${formatDuration(totalDuration)}")
    sb.appendLine("------------")
    profileInfo.clear()

    debug(sb.toString())
}

private fun formatDuration(duration: Duration): String {
    return "%.3f ms".format(duration.inWholeMicroseconds.toDouble() / 1000)
}

fun debug(message: String) = logger.log(LogLevel.Debug, message)
fun info(message: String) = logger.log(LogLevel.Info, message)
fun error(message: String) = logger.log(LogLevel.Error, message)
fun trace(message: String) = logger.log(LogLevel.Trace, message)
fun warn(message: String) = logger.log(LogLevel.Warning, message)

private class JULRedirector: Handler() {
    override fun publish(record: LogRecord) {
        when (record.level) {
            Level.SEVERE -> {
                error(record.message)
                val stackTrace = StringWriter().also { PrintWriter(it).use { pw -> record.thrown?.printStackTrace(pw) } }.toString()
                if(stackTrace.isNotEmpty()) error(stackTrace)
            }
            Level.WARNING -> warn(record.message)
            Level.INFO -> info(record.message)
            Level.CONFIG -> debug(record.message)
            Level.FINE -> trace(record.message)
            else -> trace(record.message)
        }
    }

    override fun flush() {}
    override fun close() {}
}
