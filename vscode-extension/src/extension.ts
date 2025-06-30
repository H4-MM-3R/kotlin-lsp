import * as vscode from 'vscode';
import * as path from 'path';
import * as fs from 'fs';
import {
    LanguageClient,
    LanguageClientOptions,
    ServerOptions,
    TransportKind,
    Executable
} from 'vscode-languageclient/node';

let client: LanguageClient;

export function activate(context: vscode.ExtensionContext) {
    const config = vscode.workspace.getConfiguration('kotlinLsp');
    
    if (!config.get('enabled')) {
        console.log('Kotlin LSP is disabled');
        return;
    }

    // Get server executable path
    const serverPath = getServerPath(config);
    if (!serverPath) {
        vscode.window.showErrorMessage('Kotlin LSP server not found. Please install or configure server path.');
        return;
    }

    // Configure server options
    const serverOptions: ServerOptions = createServerOptions(serverPath, config);
    
    // Configure client options
    const clientOptions: LanguageClientOptions = {
        documentSelector: [
            { scheme: 'file', language: 'kotlin' }
        ],
        synchronize: {
            fileEvents: [
                vscode.workspace.createFileSystemWatcher('**/*.kt'),
                vscode.workspace.createFileSystemWatcher('**/*.kts'),
                vscode.workspace.createFileSystemWatcher('**/build.gradle'),
                vscode.workspace.createFileSystemWatcher('**/build.gradle.kts'),
                vscode.workspace.createFileSystemWatcher('**/settings.gradle'),
                vscode.workspace.createFileSystemWatcher('**/settings.gradle.kts')
            ]
        },
        outputChannel: vscode.window.createOutputChannel('Kotlin LSP'),
        traceOutputChannel: vscode.window.createOutputChannel('Kotlin LSP Trace')
    };

    // Create the language client
    client = new LanguageClient(
        'kotlinLsp',
        'Kotlin Language Server',
        serverOptions,
        clientOptions
    );

    // Register commands
    context.subscriptions.push(
        vscode.commands.registerCommand('kotlinLsp.restart', () => {
            restartServer();
        })
    );

    // Start the client and server
    startServer();
}

export function deactivate(): Thenable<void> | undefined {
    if (!client) {
        return undefined;
    }
    return client.stop();
}

function getServerPath(config: vscode.WorkspaceConfiguration): string | null {
    // Check user-configured path first
    const configuredPath = config.get<string>('serverPath');
    if (configuredPath && fs.existsSync(configuredPath)) {
        return configuredPath;
    }

    // Look for bundled server in extension directory
    const extensionPath = vscode.extensions.getExtension('your-publisher.kotlin-lsp-vscode')?.extensionPath;
    if (extensionPath) {
        const bundledServerPath = path.join(extensionPath, 'server', 'bin', 'kotlin-lsp');
        if (fs.existsSync(bundledServerPath)) {
            return bundledServerPath;
        }
    }

    // Look for server in workspace
    const workspaceFolder = vscode.workspace.workspaceFolders?.[0];
    if (workspaceFolder) {
        const workspaceServerPath = path.join(workspaceFolder.uri.fsPath, 'lsp-dist', 'kotlin-lsp-0.1a', 'bin', 'kotlin-lsp');
        if (fs.existsSync(workspaceServerPath)) {
            return workspaceServerPath;
        }
    }

    // Try to find kotlin-lsp in PATH
    const which = process.platform === 'win32' ? 'where' : 'which';
    try {
        const { execSync } = require('child_process');
        const output = execSync(`${which} kotlin-lsp`).toString().trim();
        if (output && fs.existsSync(output)) {
            return output;
        }
    } catch (error) {
        // kotlin-lsp not found in PATH
    }

    return null;
}

function createServerOptions(serverPath: string, config: vscode.WorkspaceConfiguration): ServerOptions {
    const javaHome = config.get<string>('javaHome');
    const isWindows = process.platform === 'win32';
    
    // Determine the executable to use
    let command = serverPath;
    if (isWindows && !serverPath.endsWith('.bat')) {
        // On Windows, try .bat version first
        const batPath = serverPath + '.bat';
        if (fs.existsSync(batPath)) {
            command = batPath;
        }
    }

    const executable: Executable = {
        command: command,
        transport: TransportKind.stdio,
        options: {
            env: {
                ...process.env,
                ...(javaHome && { JAVA_HOME: javaHome })
            }
        }
    };

    return executable;
}

async function startServer() {
    try {
        await client.start();
        vscode.window.showInformationMessage('Kotlin LSP server started successfully');
    } catch (error) {
        vscode.window.showErrorMessage(`Failed to start Kotlin LSP server: ${error}`);
    }
}

async function restartServer() {
    if (client) {
        await client.stop();
    }
    await startServer();
    vscode.window.showInformationMessage('Kotlin LSP server restarted');
} 