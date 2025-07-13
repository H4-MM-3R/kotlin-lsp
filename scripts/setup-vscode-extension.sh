#!/bin/bash

# A setup script to build and prepare the Kotlin LSP VSCode extension.

set -e
set -u 

PROJECT_ROOT=$(git rev-parse --show-toplevel)
EXTENSION_DIR="$PROJECT_ROOT/vscode-extension"
SCRIPTS_DIR="$PROJECT_ROOT/scripts"

BLUE='\033[1;34m'
NONE='\033[0m' 
YELLOW='\033[1;33m'
MAGENTA='\033[1;35m'

function check_dependency() {
    if ! command -v "$1" &> /dev/null; then
        echo "❌ Error: Required dependency '$1' is not installed or not in your PATH."
        if [ "$1" = "vsce" ]; then
            echo " Please install it by running 'npm install -g vsce'."
            exit 1
        fi
    fi


}

echo -e "\n${MAGENTA}============================================== ${NONE}"
echo -e "${MAGENTA}=== Setting up Kotlin LSP VSCode Extension === ${NONE}"
echo -e "${MAGENTA}============================================== ${NONE}"
cd "$PROJECT_ROOT" 

echo -e "\n${YELLOW}Verifying required tools${NONE}"
check_dependency "git"
check_dependency "java"
check_dependency "node"
check_dependency "npm"
check_dependency "vsce"
echo -e "✅ All dependencies found. \n"

if ! "$SCRIPTS_DIR/build.sh"; then
    echo "❌ Server build failed. Aborting."
    exit 1
fi

echo -e "\n${YELLOW}Setting up the VSCode extension...${NONE}"
echo -e "\n ${BLUE} --> Installing npm dependencies in '$EXTENSION_DIR' ${NONE}"
if ! npm install --prefix "$EXTENSION_DIR"; then
    echo "❌ npm install failed. Aborting."
    exit 1
fi

if ! "$SCRIPTS_DIR/bundle-server.sh"; then
    echo -e "\n❌ Server bundling failed. Aborting."
    exit 1
fi

echo -e "\n${YELLOW}Compiling the extension TypeScript...${NONE}"
if ! npm run compile --prefix "$EXTENSION_DIR"; then
    echo -e "\n❌ TypeScript compilation failed. Aborting."
    exit 1
fi

echo -e "\n${YELLOW}Packaging the extension...${NONE}"
cd "$EXTENSION_DIR" 
if ! vsce package; then
    echo -e "\n❌ Extension packaging failed. Aborting."
    exit 1
fi

echo -e "✅ Development setup complete!\n"
echo -e "To test the extension:\n"
echo "   1. Open the '$EXTENSION_DIR' directory in VSCode."
echo "   2. Press F5 to launch the Extension Development Host."
echo "   3. Open a Kotlin project in the new window to activate the extension." 
echo -e "\n or \n" 
echo " drag and drop the .vsix file in $EXTENSION_DIR to VSCode extensions tab." 

