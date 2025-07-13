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
- ✅ Code actions (Add imports)

## Requirements

- Java 21 or higher
- VS Code 1.74.0 or higher
- A Kotlin project with Gradle build system

## Installation

```bash
${ROOT_DIR}/scripts/setup-vscode-extension.sh
```

## Usage

#### Development
To run the extension in development mode, open the current folder in VS Code and press `F5`.
now open a kotlin project in the new debug window

#### Package
To use the package, drag and drop the .vsix file into the VS Code extensions tab.
