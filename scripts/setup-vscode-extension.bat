@echo off
setlocal enabledelayedexpansion

REM A setup script to build and prepare the Kotlin LSP VSCode extension.

REM Get project root directory (assuming we're in scripts subfolder)
for %%I in ("%~dp0..") do set "PROJECT_ROOT=%%~fI"
set "EXTENSION_DIR=%PROJECT_ROOT%\vscode-extension"
set "SCRIPTS_DIR=%PROJECT_ROOT%\scripts"

echo.
echo ==============================================
echo === Setting up Kotlin LSP VSCode Extension ===
echo ==============================================

cd /d "%PROJECT_ROOT%"

echo.
echo Verifying required tools
call :check_dependency git
if errorlevel 1 exit /b 1
call :check_dependency java
if errorlevel 1 exit /b 1
call :check_dependency node
if errorlevel 1 exit /b 1
call :check_dependency npm
if errorlevel 1 exit /b 1
call :check_dependency vsce
if errorlevel 1 exit /b 1
echo All dependencies found.
echo.

echo Building the server...
call "%SCRIPTS_DIR%\build.bat"
if errorlevel 1 (
    echo Build failed. Aborting.
    exit /b 1
)

echo.
echo Setting up the VSCode extension...
echo Installing npm dependencies in '%EXTENSION_DIR%'
cd /d "%EXTENSION_DIR%"
call npm install
if errorlevel 1 (
    echo npm install failed. Aborting.
    exit /b 1
)

echo.
echo Bundling the server...
call "%SCRIPTS_DIR%\bundle-server.bat"
if errorlevel 1 (
    echo Server bundling failed. Aborting.
    exit /b 1
)

echo.
echo Compiling the extension TypeScript...
cd /d "%EXTENSION_DIR%"
call npm run compile
if errorlevel 1 (
    echo TypeScript compilation failed. Aborting.
    exit /b 1
)

echo.
echo Packaging the extension...
cd /d "%EXTENSION_DIR%"
call vsce package
if errorlevel 1 (
    echo Extension packaging failed. Aborting.
    exit /b 1
)

echo.
echo Development setup complete!
echo.
echo To test the extension:
echo    1. Open the '%EXTENSION_DIR%' directory in VSCode.
echo    2. Press F5 to launch the Extension Development Host.
echo    3. Open a Kotlin project in the new window to activate the extension.
echo.
echo or
echo.
echo drag and drop the .vsix file in %EXTENSION_DIR% to VSCode extensions tab.
echo.

goto :eof

:check_dependency
where %1 >nul 2>&1
if errorlevel 1 (
    echo Error: Required dependency '%1' is not installed or not in your PATH.
    if "%1"=="vsce" (
        echo  Please install it by running 'npm install -g @vscode/vsce'.
    )
    exit /b 1
)
goto :eof 