#!/bin/bash

# Script to bundle the Kotlin LSP server with the VS Code extension

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
EXTENSION_DIR="$SCRIPT_DIR"
SERVER_DIR="$EXTENSION_DIR/server"

echo "Bundling Kotlin LSP server with VS Code extension..."

# Check if the LSP server is built
LSP_DIST="$PROJECT_ROOT/lsp-dist/kotlin-lsp-0.1a"
if [ ! -d "$LSP_DIST" ]; then
    echo "Error: LSP server not found at $LSP_DIST"
    echo "Please build the server first with: ./gradlew installDist"
    exit 1
fi

# Create server directory in extension
echo "Creating server directory..."
rm -rf "$SERVER_DIR"
mkdir -p "$SERVER_DIR"

# Copy the entire LSP distribution
echo "Copying LSP server files..."
cp -r "$LSP_DIST"/* "$SERVER_DIR/"

# Make scripts executable
echo "Making scripts executable..."
chmod +x "$SERVER_DIR/bin/kotlin-lsp" 2>/dev/null || true
chmod +x "$SERVER_DIR/bin/kotlin-lsp.bat" 2>/dev/null || true

# Update package.json to include the server in the extension package
echo "Server bundled successfully!"
echo "Server location: $SERVER_DIR"
echo ""
echo "To test the extension:"
echo "1. cd $EXTENSION_DIR"
echo "2. npm install"
echo "3. npm run compile"
echo "4. Open in VS Code and press F5"
echo ""
echo "To package the extension:"
echo "1. npm install -g vsce"
echo "2. vsce package" 