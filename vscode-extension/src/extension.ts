import * as vscode from 'vscode';
import * as path from 'path';
import * as fs from 'fs';
import * as net from 'net';
import {
    LanguageClient,
    LanguageClientOptions,
    ServerOptions,
    TransportKind,
    Executable,
    StreamInfo,
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

    // Determine transport (TCP or stdio)
    const useTcp = config.get<boolean>('tcp.enabled');
    let serverOptions: ServerOptions;
    if (useTcp) {
        serverOptions = createTcpServerOptions(config);
    } else {
        // Get server executable path
        const serverPath = getServerPath(config);
        if (!serverPath) {
            vscode.window.showErrorMessage('Kotlin LSP server not found. Please install or configure server path.');
            return;
        }
        // Configure server options
        serverOptions = createServerOptions(serverPath, config);
    }
    
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
        const workspaceServerPath = path.join(workspaceFolder.uri.fsPath, 'lsp-dist', 'kotlin-lsp-2.0', 'bin', 'kotlin-lsp');
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

function createTcpServerOptions(config: vscode.WorkspaceConfiguration): ServerOptions {
    let host = config.get<string>('tcp.host') || '127.0.0.1';
    const port = config.get<number>('tcp.port') || 2090;
    const connectTimeoutMs = 10_000;

    // 0.0.0.0 / :: are listen-only addresses; for client connections use loopback
    if (host === '0.0.0.0' || host === '::') {
        host = '127.0.0.1';
        vscode.window.showWarningMessage('[Kotlin LSP] TCP host 0.0.0.0/:: is for listening; connecting to 127.0.0.1 instead');
    }

    const streamProvider = (): Promise<StreamInfo> => {
        return new Promise((resolve, reject) => {
            const socket = net.connect({ host, port });

            const onError = (err: unknown) => {
                cleanup();
                reject(err);
            };

            const onTimeout = () => {
                cleanup();
                reject(new Error(`Connection to ${host}:${port} timed out`));
            };

            const onConnect = () => {
                cleanup(false);
                resolve({ reader: socket, writer: socket });
            };

            const cleanup = (destroy: boolean = true) => {
                socket.removeListener('error', onError);
                socket.removeListener('timeout', onTimeout);
                socket.removeListener('connect', onConnect);
                if (destroy) {
                    try { socket.destroy(); } catch { /* noop */ }
                }
            };

            socket.setTimeout(connectTimeoutMs);
            socket.once('error', onError);
            socket.once('timeout', onTimeout);
            socket.once('connect', onConnect);
        });
    };

    return streamProvider;
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
