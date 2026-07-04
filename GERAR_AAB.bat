@echo off
chcp 65001 >nul
cd /d "%~dp0"

rem ---------- localizar o Java do Android Studio ----------
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" goto java_ok
set "JAVA_HOME="
if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jbr"
if not defined JAVA_HOME if exist "%LOCALAPPDATA%\Programs\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=%LOCALAPPDATA%\Programs\Android Studio\jbr"
if not defined JAVA_HOME if exist "%ProgramFiles%\Android\Android Studio\jre\bin\java.exe" set "JAVA_HOME=%ProgramFiles%\Android\Android Studio\jre"
if not defined JAVA_HOME (
    for /d %%D in ("%ProgramFiles%\Java\jdk*") do if exist "%%D\bin\java.exe" set "JAVA_HOME=%%D"
)
if not defined JAVA_HOME (
    echo ERRO: Java nao encontrado. Instale o Android Studio primeiro.
    pause
    exit /b 1
)
:java_ok
set "PATH=%JAVA_HOME%\bin;%PATH%"
echo Java: %JAVA_HOME%

rem ---------- localizar o SDK do Android ----------
if exist "local.properties" goto sdk_ok
set "SDKP="
if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools" set "SDKP=%LOCALAPPDATA%\Android\Sdk"
if not defined SDKP if defined ANDROID_HOME if exist "%ANDROID_HOME%\platform-tools" set "SDKP=%ANDROID_HOME%"
if not defined SDKP (
    echo ERRO: SDK do Android nao encontrado.
    echo Abra esta pasta uma vez no Android Studio para ele baixar o SDK.
    pause
    exit /b 1
)
set "SDKP=%SDKP:\=/%"
echo sdk.dir=%SDKP%> local.properties
echo SDK: %SDKP%
:sdk_ok
echo.
title RXSDR-APP - Gerar AAB (Play Store)
echo ============================================
echo   RXSDR-APP - Gerando .AAB para a Play Store
echo ============================================
echo.
if not exist "keystore.properties" (
    echo ERRO: keystore nao encontrada!
    echo Rode primeiro o GERAR_KEYSTORE.bat
    pause
    exit /b 1
)
call gradlew.bat bundleRelease --console=plain
if errorlevel 1 (
    echo.
    echo ERRO na compilacao! Veja as mensagens acima.
    pause
    exit /b 1
)
copy /y "app\build\outputs\bundle\release\app-release.aab" "RXSDR-APP.aab" >nul
echo.
echo ============================================
echo  PRONTO! Arquivo gerado: RXSDR-APP.aab
echo  Assinado e pronto para enviar a Play Store.
echo ============================================
pause
