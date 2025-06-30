# Kotlin Language Server VS Code Extension

This VS Code extension provides Kotlin language support powered by the Kotlin Analysis API through a Language Server Protocol (LSP) implementation.

## Features

- ✅ Syntax highlighting
- ✅ Diagnostics (syntax and semantic errors)
- ✅ Go to Definition
- ✅ Go to Implementation
- ✅ Find References
- ✅ Rename refactoring
- ✅ Document Symbols
- ✅ Hover information
- ✅ Code completion (dot-based)
- ✅ Code actions (Add imports)

## Requirements

- Java 21 or higher
- VS Code 1.74.0 or higher
- A Kotlin project with Gradle build system

## Installation

### Option 1: Development Setup (Recommended for testing)

1. Clone or copy the Kotlin LSP server to your system
2. Build the server:
   ```bash
   ./gradlew installDist
   ```
3. Install this extension:
   ```bash
   cd vscode-extension
   npm install
   npm run compile
   ```
4. Open the extension in VS Code and press F5 to launch a new Extension Development Host

### Option 2: Manual Configuration

1. Install the extension
2. Configure the server path in VS Code settings:
   ```json
   {
     "kotlinLsp.serverPath": "/path/to/kotlin-lsp/lsp-dist/kotlin-lsp-0.1a/bin/kotlin-lsp"
   }
   ```

### Option 3: Auto-discovery

The extension will automatically look for the Kotlin LSP server in these locations:
1. Path specified in `kotlinLsp.serverPath` setting
2. Bundled with extension (if packaged)
3. Current workspace under `lsp-dist/kotlin-lsp-0.1a/bin/kotlin-lsp`
4. System PATH

## Configuration

Configure the extension through VS Code settings:

```json
{
  "kotlinLsp.enabled": true,
  "kotlinLsp.serverPath": "/path/to/kotlin-lsp-server",
  "kotlinLsp.javaHome": "/path/to/java/home",
  "kotlinLsp.trace.server": "off"
}
```

### Available Settings

- `kotlinLsp.enabled`: Enable/disable the Kotlin LSP (default: `true`)
- `kotlinLsp.serverPath`: Path to the Kotlin LSP executable
- `kotlinLsp.javaHome`: Java home directory for running the LSP server
- `kotlinLsp.trace.server`: Trace communication between VS Code and the server (`off`, `messages`, `verbose`)

## Commands

- `kotlinLsp.restart`: Restart the Kotlin LSP server

Access commands via Command Palette (Ctrl+Shift+P / Cmd+Shift+P) → type "Kotlin LSP"

## Supported File Types

- `.kt` - Kotlin source files
- `.kts` - Kotlin script files

The extension automatically activates when you open a Kotlin file.

## Project Structure

The extension monitors changes to:
- `**/*.kt` - Kotlin source files
- `**/*.kts` - Kotlin script files  
- `**/build.gradle` - Gradle build files
- `**/build.gradle.kts` - Kotlin Gradle build files
- `**/settings.gradle` - Gradle settings
- `**/settings.gradle.kts` - Kotlin Gradle settings

## Troubleshooting

### Server Not Starting

1. **Check Java version**: Ensure Java 21+ is installed
   ```bash
   java -version
   ```

2. **Check server path**: Verify the server executable exists and is executable
   ```bash
   ls -la /path/to/kotlin-lsp
   ```

3. **Check logs**: Open VS Code Output panel → select "Kotlin LSP" channel

4. **Manual test**: Try running the server manually:
   ```bash
   /path/to/kotlin-lsp --version
   ```

### Performance Issues

- The server performs indexing on startup which may take time for large projects
- Check memory usage and consider increasing JVM heap size if needed
- Ensure your project's Gradle cache is properly set up

### Language Features Not Working

- Ensure your project builds successfully with Gradle
- Check that all dependencies are resolved
- Look for error messages in the Kotlin LSP output channel

## Development

To contribute or modify this extension:

1. Clone the repository
2. Install dependencies: `npm install`
3. Compile TypeScript: `npm run compile`
4. Open in VS Code and press F5 to test

## License

This extension is provided under the same license as the Kotlin LSP server. 