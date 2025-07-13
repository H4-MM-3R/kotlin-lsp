#!/bin/bash

# A script to build the Kotlin LSP server and prepare the distribution package.

set -e 
set -u 

PROJECT_ROOT=$(git rev-parse --show-toplevel)
APP_BUILD_DIR="$PROJECT_ROOT/app/build/distributions"
DIST_DIR="$PROJECT_ROOT/lsp-dist"

BLUE='\033[1;34m'
NONE='\033[0m' 
YELLOW='\033[1;33m'

echo -e "\n${YELLOW}Building the Kotlin LSP server...${NONE}"

cd "$PROJECT_ROOT"
echo -e "\n ${BLUE} --> Running './gradlew clean distZip'${NONE}\n"
if ! ./gradlew clean distZip; then
    echo "❌ Gradle build failed."
    exit 1
fi

echo -e "\n ${BLUE} --> Cleaning and recreating '$DIST_DIR' directory${NONE}\n"
# echo "\033[1;34m\033[0m"
rm -rf "$DIST_DIR"
mkdir -p "$DIST_DIR"

ZIP_FILE=$(find "$APP_BUILD_DIR" -name "kotlin-lsp-*.zip" -print -quit)

if [ -z "$ZIP_FILE" ]; then
    echo "❌ Build error: Distribution zip file not found in '$APP_BUILD_DIR'."
    exit 1
fi

echo -e " ${BLUE} --> Unpacking '$ZIP_FILE' to '$DIST_DIR'${NONE}\n"
unzip -q "$ZIP_FILE" -d "$DIST_DIR"

printf "✅ Build complete. Server distribution is ready in '%s'.\n" "$DIST_DIR"
