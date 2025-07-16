## Build from Source

1.  **Clone the repository:**
    ```bash
    git clone https://github.com/h4-mm-3r/kotlin-lsp.git
    cd kotlin-lsp
    ```

2.  **Build the language server:**
    #### for Linux
    ```bash
    ./scripts/build.sh
    ```
    #### for Windows
    ```bat
    scripts\build.bat
    ```
3.  **Build the VSCode Extension:**
    #### for Linux
    ```bash
    cd vscode-extension
    npm install -g @vscode/vsce
    npm install
    cd ../scripts/setup-vscode-extension.sh
    ```

    #### for Windows
    ```bat
    cd vscode-extension
    npm install -g @vscode/vsce
    npm install
    cd ..\scripts\setup-vscode-extension.bat
    ```
    This will create a `.vsix` file in the `vscode-extension` directory.
