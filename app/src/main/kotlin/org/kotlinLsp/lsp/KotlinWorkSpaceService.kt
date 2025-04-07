package org.kotlinLsp.lsp

import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.services.WorkspaceService

class KotlinWorkSpaceService: WorkspaceService {
    override fun didChangeConfiguration(params: DidChangeConfigurationParams?) {
        Logger.info("Configuration changed")
    }

    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams?) {
        Logger.info("Watched files changed")
    }
}
