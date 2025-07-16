@echo off
setlocal enabledelayedexpansion

REM A script to bundle the Kotlin LSP server with the VSCode extension.

REM Get project root directory (assuming we're in scripts subfolder)
for %%I in ("%~dp0..") do set "PROJECT_ROOT=%%~fI"
set "EXTENSION_DIR=%PROJECT_ROOT%\vscode-extension"
set "LSP_DIST_DIR=%PROJECT_ROOT%\lsp-dist"
set "SERVER_BUNDLE_DIR=%EXTENSION_DIR%\server"

echo.
echo Bundling Kotlin LSP server with VSCode extension...
echo.

REM Find the LSP build directory (first subdirectory in lsp-dist)
set "LSP_BUILD_DIR="
for /d %%d in ("%LSP_DIST_DIR%\*") do (
    if exist "%%d\bin" (
        set "LSP_BUILD_DIR=%%d"
        goto found_build_dir
    )
)

:found_build_dir
if not defined LSP_BUILD_DIR (
    echo Error: LSP server build not found in '%LSP_DIST_DIR%'.
    echo    Please build the server first by running: scripts\build.bat
    exit /b 1
)

echo Found server build at: %LSP_BUILD_DIR%
echo.

echo Cleaning and recreating bundle directory: '%SERVER_BUNDLE_DIR%'...
echo.
if exist "%SERVER_BUNDLE_DIR%" rmdir /s /q "%SERVER_BUNDLE_DIR%"
mkdir "%SERVER_BUNDLE_DIR%"

echo Copying server files...
echo.
xcopy "%LSP_BUILD_DIR%\*" "%SERVER_BUNDLE_DIR%\" /e /i /h /y
if errorlevel 1 (
    echo Failed to copy server files.
    exit /b 1
)

echo Making launch scripts executable...
echo.
set "KOTLIN_LSP_SCRIPT=%SERVER_BUNDLE_DIR%\bin\kotlin-lsp"
set "KOTLIN_LSP_BAT_SCRIPT=%SERVER_BUNDLE_DIR%\bin\kotlin-lsp.bat"

if exist "%KOTLIN_LSP_SCRIPT%" (
    REM On Windows, we don't need chmod, files are executable by default
    echo kotlin-lsp script found.
) else (
    echo Warning: 'kotlin-lsp' script not found.
)

if exist "%KOTLIN_LSP_BAT_SCRIPT%" (
    echo kotlin-lsp.bat script found.
) else (
    echo Warning: 'kotlin-lsp.bat' script not found.
)

echo.
echo Server bundled successfully into '%SERVER_BUNDLE_DIR%'. 