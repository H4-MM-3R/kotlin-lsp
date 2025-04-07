package org.kotlinLsp

import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageClient
import org.kotlinLsp.lsp.KotlinLanguageServer


fun main(){
    val server = KotlinLanguageServer()
    val launcher: Launcher<LanguageClient> = LSPLauncher.createServerLauncher(server, System.`in`, System.out)

    server.connect(launcher.remoteProxy)
    launcher.startListening()
}
