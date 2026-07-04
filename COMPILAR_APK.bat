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
title RXSDR-APP - Compilar APK
echo ============================================
echo   RXSDR-APP - Compilando APK de instalacao
echo ============================================
echo.
if exist "keystore.properties" (
    echo Assinatura: keystore encontrada - APK RELEASE assinado
    call gradlew.bat assembleRelease --console=plain
    if errorlevel 1 goto erro
    copy /y "app\build\outputs\apk\release\app-release.apk" "RXSDR-APP.apk" >nul
) else (
    echo Assinatura: sem keystore - APK DEBUG para teste
    echo ^(rode GERAR_KEYSTORE.bat para poder gerar o APK final^)
    call gradlew.bat assembleDebug --console=plain
    if errorlevel 1 goto erro
    copy /y "app\build\outputs\apk\debug\app-debug.apk" "RXSDR-APP.apk" >nul
)
echo.
echo ============================================
echo  PRONTO! APK gerado: RXSDR-APP.apk
echo  Copie para o celular e instale.
echo ============================================
pause
exit /b 0
:erro
echo.
echo ERRO na compilacao! Veja as mensagens acima.
echo Na primeira vez o Gradle baixa varios componentes - precisa de internet.
pause
exit /b 1
