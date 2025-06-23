package org.kotlinlsp

import org.eclipse.lsp4j.launch.LSPLauncher
import org.kotlinlsp.lsp.KotlinLanguageServer
import org.kotlinlsp.common.getLspVersion
import org.kotlinlsp.common.profileJvmStartup
import org.kotlinlsp.lsp.KotlinLanguageServerNotifier
import java.util.concurrent.Executors
import kotlin.system.exitProcess
import java.net.ServerSocket
import java.net.Socket

fun main(args: Array<String>) {
    profileJvmStartup()
    if ("-v" in args || "--version" in args) {
        println(getLspVersion())
        return
    }

    // Detect TCP mode
    val portFlagIndex = args.indexOf("--port")
    val tcpEnabled = "--tcp" in args || portFlagIndex != -1

    if (tcpEnabled) {
        // Determine port (default 2090 if not specified)
        val port = if (portFlagIndex != -1 && portFlagIndex + 1 < args.size) {
            args[portFlagIndex + 1].toIntOrNull() ?: 2090
        } else 2090

        startTcpServer(port)
    } else {
        startStdioServer()
    }
}

private fun startStdioServer() {
    val notifier = object : KotlinLanguageServerNotifier {
        override fun onExit() {
            exitProcess(0)
        }
    }
    val executor = Executors.newSingleThreadExecutor {
        Thread(it, "KotlinLSP-LSP")
    }
    val server = KotlinLanguageServer(notifier)
    val launcher = LSPLauncher.createServerLauncher(server, System.`in`, System.out, executor) { it }

    server.connect(launcher.remoteProxy)
    launcher.startListening()
}

private fun startTcpServer(port: Int) {
    println("Kotlin LSP listening on 0.0.0.0:$port (TCP)...")
    val serverSocket = ServerSocket(port)
    while (true) {
        val clientSocket: Socket = serverSocket.accept()
        val notifier = object : KotlinLanguageServerNotifier {
            override fun onExit() {
                // Close the client socket when the server requests exit
                try {
                    clientSocket.close()
                } catch (_: Exception) {}
            }
        }

        val executor = Executors.newSingleThreadExecutor {
            Thread(it, "KotlinLSP-LSP")
        }
        val server = KotlinLanguageServer(notifier)
        val launcher = LSPLauncher.createServerLauncher(
            server,
            clientSocket.getInputStream(),
            clientSocket.getOutputStream(),
            executor
        ) { it }

        server.connect(launcher.remoteProxy)

        // Handle the connection synchronously; once it ends, loop back to accept a new one
        launcher.startListening()
    }
}
