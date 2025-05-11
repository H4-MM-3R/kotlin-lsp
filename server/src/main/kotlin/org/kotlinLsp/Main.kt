package org.kotlinLsp

import org.eclipse.lsp4j.launch.LSPLauncher
import java.util.concurrent.Executors

fun main() {
    val executor = Executors.newSingleThreadExecutor{
        Thread(it, "client");
    }
    val lsp = KotlinLanguageServer();
    val launcher = LSPLauncher.createServerLauncher(lsp, System.`in`, System.out, executor) { it }

    lsp.connect(launcher.remoteProxy)
    launcher.startListening()
}
