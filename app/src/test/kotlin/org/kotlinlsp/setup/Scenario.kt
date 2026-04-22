/*
 * Copyright 2026  Kumarapu Hemram
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package org.kotlinlsp.setup

import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.services.LanguageClient
import org.kotlinlsp.lsp.KotlinLanguageServer
import org.kotlinlsp.common.removeCacheFolder
import org.kotlinlsp.lsp.KotlinLanguageServerNotifier
import org.mockito.Mockito
import org.mockito.Mockito.*
import java.io.File
import java.nio.file.Paths

fun scenario(
    projectName: String,
    testCase: (server: KotlinLanguageServer, client: LanguageClient, projectUrl: String, notifier: KotlinLanguageServerNotifier) -> Unit
) {
    // Setup
    val cwd = Paths.get("").toAbsolutePath().toString()
    val jdkHome = System.getProperty("java.home")
    val moduleContents = """
            [
              {
                "id": "main",
                "dependencies": [
                    "JDK 21"
                ],
                "javaVersion": "21",
                "contentRoots": ["$cwd/test-projects/$projectName"],
                "isSource": true,
                "kotlinVersion": "2.1"
              },
              {
                "id": "JDK 21",
                "dependencies": [],
                "javaVersion": "21",
                "isJdk": true,
                "isSource": false,
                "contentRoots": ["$jdkHome"]
              }
            ]
        """.trimIndent()
    val moduleFile = File("$cwd/test-projects/$projectName/.kotlinlsp-modules.json")
    moduleFile.delete()
    removeCacheFolder("$cwd/test-projects/$projectName")
    moduleFile.writeText(moduleContents)

    val notifier = mock(KotlinLanguageServerNotifier::class.java)
    val server = KotlinLanguageServer(notifier)
    val initParams = InitializeParams().apply {
        workspaceFolders = listOf(
            WorkspaceFolder().apply {
                uri = "file://$cwd/test-projects/$projectName"
            }
        )
    }

    val client = mock(LanguageClient::class.java)
    `when`(client.logMessage(any())).thenAnswer {
        val params = it.getArgument<MessageParams>(0)
        println("[${params.type.toString().uppercase()}]: ${params.message}")
    }
    server.connect(client)
    server.initialize(initParams).join()
    server.initialized(InitializedParams())

    // Run test case
    try {
        testCase(server, client, "file://$cwd/test-projects/$projectName", notifier)
    } finally {
        // Cleanup
        server.shutdown().join()
        moduleFile.delete()
        removeCacheFolder("$cwd/test-projects/$projectName")
    }
}
