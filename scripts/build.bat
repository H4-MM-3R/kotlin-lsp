@echo off
setlocal enabledelayedexpansion

REM A script to build the Kotlin LSP server and prepare the distribution package.

REM Get project root directory (assuming we're in scripts subfolder)
for %%I in ("%~dp0..") do set "PROJECT_ROOT=%%~fI"
set "APP_BUILD_DIR=%PROJECT_ROOT%\app\build\distributions"
set "DIST_DIR=%PROJECT_ROOT%\lsp-dist"

echo.
echo Building the Kotlin LSP server...

cd /d "%PROJECT_ROOT%"
echo.
echo Running 'gradlew.bat clean distZip'
echo.
call gradlew.bat clean distZip
if errorlevel 1 (
    echo Gradle build failed.
    exit /b 1
)

echo.
echo Cleaning and recreating '%DIST_DIR%' directory
echo.
if exist "%DIST_DIR%" rmdir /s /q "%DIST_DIR%"
mkdir "%DIST_DIR%"

REM Find the zip file (looking for kotlin-lsp-*.zip)
set "ZIP_FILE="
for %%f in ("%APP_BUILD_DIR%\kotlin-lsp-*.zip") do (
    if exist "%%f" set "ZIP_FILE=%%f"
)

if not defined ZIP_FILE (
    echo Build error: Distribution zip file not found in '%APP_BUILD_DIR%'.
    exit /b 1
)

echo Unpacking '%ZIP_FILE%' to '%DIST_DIR%'
echo.
powershell -command "Expand-Archive -Path '%ZIP_FILE%' -DestinationPath '%DIST_DIR%' -Force"
if errorlevel 1 (
    echo Failed to extract zip file.
    exit /b 1
)

echo Build complete. Server distribution is ready in '%DIST_DIR%'. 