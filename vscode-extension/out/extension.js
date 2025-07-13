"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || function (mod) {
    if (mod && mod.__esModule) return mod;
    var result = {};
    if (mod != null) for (var k in mod) if (k !== "default" && Object.prototype.hasOwnProperty.call(mod, k)) __createBinding(result, mod, k);
    __setModuleDefault(result, mod);
    return result;
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.deactivate = exports.activate = void 0;
const vscode = __importStar(require("vscode"));
const path = __importStar(require("path"));
const fs = __importStar(require("fs"));
const node_1 = require("vscode-languageclient/node");
let client;
function activate(context) {
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
    const serverOptions = createServerOptions(serverPath, config);
    // Configure client options
    const clientOptions = {
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
        revealOutputChannelOn: node_1.RevealOutputChannelOn.Error | node_1.RevealOutputChannelOn.Info | node_1.RevealOutputChannelOn.Warn,
        errorHandler: {
            error: (error, message, count) => {
                console.error('Kotlin LSP error:', error);
                return { action: node_1.ErrorAction.Continue };
            },
            closed: () => {
                console.error('[KOTLIN LSP] Connection closed');
                return { action: node_1.CloseAction.Restart };
            }
        }
    };
    // Create the language client
    client = new node_1.LanguageClient('kotlinLsp', 'Kotlin Language Server', serverOptions, clientOptions);
    // Register commands
    context.subscriptions.push(vscode.commands.registerCommand('kotlinLsp.restart', () => {
        restartServer();
    }));
    // Start the client and server
    startServer();
}
exports.activate = activate;
function deactivate() {
    if (!client) {
        return undefined;
    }
    return client.stop();
}
exports.deactivate = deactivate;
function getServerPath(config) {
    // Check user-configured path first
    const configuredPath = config.get('serverPath');
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
    }
    catch (error) {
        // kotlin-lsp not found in PATH
    }
    return null;
}
function createServerOptions(serverPath, config) {
    const javaHome = config.get('javaHome');
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
    const executable = {
        command: command,
        transport: node_1.TransportKind.stdio,
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
    }
    catch (error) {
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
//# sourceMappingURL=extension.js.map