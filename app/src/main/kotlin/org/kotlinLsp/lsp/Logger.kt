package org.kotlinLsp.lsp

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object Logger {
    private val logFile = File("/home/hemram/.local/lsplogs/log.txt")
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    init {
        logFile.parentFile.mkdirs()
    }

    private fun log(level: String, message: String) {
        val timestamp = LocalDateTime.now().format(dateFormat)
        val logMessage = "$timestamp [$level] $message\n"
        logFile.appendText(logMessage)
    }

    fun info(message: String) {
        log("INFO", message)
    }

    fun warning(message: String) {
        log("WARNING", message)
    }

    fun severe(message: String) {
        log("SEVERE", message)
    }
    
    fun error(message: String) {
        log("ERROR", message)
    }
} 
