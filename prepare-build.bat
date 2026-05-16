@echo off
chcp 65001 >nul 2>&1

echo ==========================================
echo MangaSR v2 - Build Preparation Script
echo ==========================================

set "NCNN_VERSION=20250915"
set "MIRROR=https://ghfast.top"
set "NCNN_URL=%MIRROR%/https://github.com/Tencent/ncnn/releases/download/%NCNN_VERSION%/ncnn-%NCNN_VERSION%-android-vulkan.zip"
set "NCNN_DIR=core\superresolution\src\main\cpp\ncnn"

if not exist "%NCNN_DIR%" mkdir "%NCNN_DIR%"

echo.
echo MangaSR v2 - Build Preparation
echo.

REM Check if NCNN is already built-in
if exist "%NCNN_DIR%\include\ncnn" if exist "%NCNN_DIR%\lib\arm64-v8a\libncnn.a" (
    echo [INFO] NCNN library is already built-in. Skipping download.
    goto :done
)

REM Download NCNN if not found
echo [INFO] NCNN library not found. Downloading...
cd /d "%~dp0%NCNN_DIR%"
curl -L --retry 3 --connect-timeout 30 -o "ncnn.zip" "%NCNN_URL%"
if errorlevel 1 (
    echo [WARN] Mirror download failed, trying direct GitHub...
    curl -L --retry 3 --connect-timeout 30 -o "ncnn.zip" "https://github.com/Tencent/ncnn/releases/download/%NCNN_VERSION%/ncnn-%NCNN_VERSION%-android-vulkan.zip"
)

echo [INFO] Extracting NCNN...
powershell -Command "Expand-Archive -Force 'ncnn.zip' '.'"

set "SRC_DIR=ncnn-%NCNN_VERSION%-android-vulkan"
if exist "%SRC_DIR%" (
    for %%a in (arm64-v8a armeabi-v7a x86_64) do (
        if not exist "lib\%%a" mkdir "lib\%%a"
        copy /y "%SRC_DIR%\%%a\lib\libncnn.a" "lib\%%a\"
        copy /y "%SRC_DIR%\%%a\lib\libSPIRV.a" "lib\%%a\"
        copy /y "%SRC_DIR%\%%a\lib\libglslang.a" "lib\%%a\"
        copy /y "%SRC_DIR%\%%a\lib\libMachineIndependent.a" "lib\%%a\"
        copy /y "%SRC_DIR%\%%a\lib\libGenericCodeGen.a" "lib\%%a\"
        copy /y "%SRC_DIR%\%%a\lib\libglslang-default-resource-limits.a" "lib\%%a\"
        copy /y "%SRC_DIR%\%%a\lib\libOSDependent.a" "lib\%%a\"
    )
    if not exist "include\ncnn" mkdir "include\ncnn"
    copy /y "%SRC_DIR%\arm64-v8a\include\ncnn\*" "include\ncnn\"
    rmdir /s /q "%SRC_DIR%"
)
del "ncnn.zip"

cd /d "%~dp0"

:done
echo.
echo ==========================================
echo Build preparation completed!
echo You can now build the project with:
echo   gradlew.bat assembleDebug
echo ==========================================
pause
