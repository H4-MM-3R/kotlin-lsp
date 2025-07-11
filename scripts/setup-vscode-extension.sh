#!/bin/bash

# Setup script for Kotlin LSP VS Code Extension

set -e

echo "Setting up Kotlin LSP VS Code Extension..."
echo "==========================================="

# Check if we're in the right directory
if [ ! -f "gradlew" ] || [ ! -d "app" ]; then
    echo "Error: Please run this script from the kotlin-lsp project root directory"
    exit 1
fi

echo ""
echo "Step 1: Building Kotlin LSP server..."
./build.sh

echo ""
echo "Step 2: Setting up VS Code extension..."
cd ../vscode-extension

echo "Installing npm dependencies..."
npm install

echo "Bundling LSP server with extension..."
../scripts/bundle-server.sh

echo "Compiling TypeScript..."
npm run compile

cd ..
echo ""
echo "✅ Setup complete!"
echo ""
echo "How to test the extension:"
echo "1. Open VS Code in the vscode-extension directory:"
echo "   code vscode-extension"
echo "2. Press F5 to launch Extension Development Host"
echo "3. Open a Kotlin project in the new VS Code window"
echo "4. The extension should automatically start the LSP server"
echo ""
echo "How to package the extension for distribution:"
echo "1. Install vsce: npm install -g vsce"
echo "2. Package: vsce package"
echo ""
echo "Troubleshooting:"
echo "- Check VS Code Output panel → 'Kotlin LSP' for server logs"
echo "- Ensure Java 21+ is installed: java -version"
echo "- Test server manually: ./lsp-dist/kotlin-lsp-0.1a/bin/kotlin-lsp --version" 

