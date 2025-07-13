#!/bin/bash

# A script to bundle the Kotlin LSP server with the VSCode extension.

set -e 
set -u 

PROJECT_ROOT=$(git rev-parse --show-toplevel)
EXTENSION_DIR="$PROJECT_ROOT/vscode-extension"
LSP_DIST_DIR="$PROJECT_ROOT/lsp-dist"
SERVER_BUNDLE_DIR="$EXTENSION_DIR/server"

BLUE='\033[1;34m'
NONE='\033[0m' 
YELLOW='\033[1;33m'
RED='\033[1;31m'

echo -e "\n${YELLOW}Bundling Kotlin LSP server with VSCode extension...${NONE}\n"

LSP_BUILD_DIR=$(find "$LSP_DIST_DIR" -mindepth 1 -maxdepth 1 -type d -print -quit)

if [ -z "$LSP_BUILD_DIR" ] || [ ! -d "$LSP_BUILD_DIR/bin" ]; then
    echo "❌ Error: LSP server build not found in '$LSP_DIST_DIR'."
    echo "   Please build the server first by running: ./scripts/build.sh"
    exit 1
fi
echo -e " ${BLUE}--> Found server build at: $LSP_BUILD_DIR${NONE}\n"

echo -e " ${BLUE}--> Cleaning and recreating bundle directory: '$SERVER_BUNDLE_DIR'...${NONE}\n"
rm -rf "$SERVER_BUNDLE_DIR"
mkdir -p "$SERVER_BUNDLE_DIR"

echo -e " ${BLUE}--> Copying server files...${NONE}\n"
shopt -s dotglob
cp -a "$LSP_BUILD_DIR"/* "$SERVER_BUNDLE_DIR/"
shopt -u dotglob

echo -e " ${BLUE}--> Making launch scripts executable...${NONE}\n"
KOTLIN_LSP_SCRIPT="$SERVER_BUNDLE_DIR/bin/kotlin-lsp"
KOTLIN_LSP_BAT_SCRIPT="$SERVER_BUNDLE_DIR/bin/kotlin-lsp.bat"

if [ -f "$KOTLIN_LSP_SCRIPT" ]; then
    chmod +x "$KOTLIN_LSP_SCRIPT"
else
    echo -e " ${RED}Warning: 'kotlin-lsp' script not found, skipping chmod.${NONE}\n"
fi

if [ -f "$KOTLIN_LSP_BAT_SCRIPT" ]; then
    chmod +x "$KOTLIN_LSP_BAT_SCRIPT"
else
    echo -e " ${RED}--> Warning: 'kotlin-lsp.bat' script not found, skipping chmod.${NONE}\n"
fi

echo "✅ Server bundled successfully into '$SERVER_BUNDLE_DIR'." 
