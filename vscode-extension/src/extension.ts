import * as vscode from 'vscode';
import * as path from 'path';
import * as fs from 'fs';
import {
    LanguageClient,
    LanguageClientOptions,
    ServerOptions,
    TransportKind,
    Executable,
    RevealOutputChannelOn,
    ErrorAction,
    CloseAction,
} from 'vscode-languageclient/node';

let client: LanguageClient;

export function activate(context: vscode.ExtensionContext) {
    
    const config: vscode.WorkspaceConfiguration = vscode.workspace.getConfiguration('kotlinLsp');
    
    if (!config.get('enabled')) {
        vscode.window.showInformationMessage('Kotlin LSP is disabled');
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
        traceOutputChannel: vscode.window.createOutputChannel('Kotlin LSP Trace'),
        diagnosticPullOptions: {
            onChange: true,
        },
        uriConverters: {
            code2Protocol: uri => uri.toString(true),
            protocol2Code: path => vscode.Uri.parse(path) 
        },
        revealOutputChannelOn: RevealOutputChannelOn.Error | RevealOutputChannelOn.Info | RevealOutputChannelOn.Warn,
        errorHandler: {
            error: (error, _message, _count) => {
                vscode.window.showErrorMessage(`[KOTLIN LSP] error:${error}`);
                return { action: ErrorAction.Continue };
            },
            closed: () => {
                vscode.window.showErrorMessage('[KOTLIN LSP] Connection closed');
                return { action: CloseAction.Restart };
            },
        },
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
    if (!client) return undefined;
    if (client.state === 2) return client.stop();
    return undefined;
}

function getServerPath(config: vscode.WorkspaceConfiguration): string | null {
    const configuredPath = config.get<string>('serverPath');
    if (configuredPath) {
        if (fs.existsSync(configuredPath)) return configuredPath;
    }

    // Look for bundled server in extension directory
    const extensionPath = vscode.extensions.getExtension('hemram.kotlin-lsp-vscode')?.extensionPath;
    if (extensionPath) {
        const bundledServerPath = path.join(extensionPath, 'server', 'bin', 'kotlin-lsp');
        if (fs.existsSync(bundledServerPath)) {
            return bundledServerPath;
        }
    }

    // Look for server in workspace
    const workspaceFolder = vscode.workspace.workspaceFolders?.[0];
    if (workspaceFolder) {
        const workspaceServerPath = path.join(workspaceFolder.uri.fsPath, 'lsp-dist', 'kotlin-lsp-1.5', 'bin', 'kotlin-lsp');
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
            // Work-around Node 18+ breaking change on Windows: spawning .bat/.cmd requires shell:true
            ...(isWindows ? { shell: true } : {}),
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
        vscode.window.showInformationMessage('🔄 Starting Kotlin LSP server...');
        await client.start();
        vscode.window.showInformationMessage('✅ Kotlin LSP server started successfully');
    } catch (error) {
        vscode.window.showErrorMessage(`❌ Failed to start Kotlin LSP server: ${error}`);
    }
}

async function restartServer() {
    if (client) {
        await client.stop();
    }
    await startServer();
    vscode.window.showInformationMessage('Kotlin LSP server restarted');
} 
