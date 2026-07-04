@echo off
chcp 65001 >nul
title RXSDR-APP - Gerar Keystore
echo ============================================
echo   RXSDR-APP - Criacao da chave de assinatura
echo ============================================
echo.
if exist "%~dp0rxsdrapp.keystore" (
    echo A keystore ja existe: rxsdrapp.keystore
    echo Nada a fazer. Se quiser criar outra, apague o arquivo antes.
    pause
    exit /b 0
)

set "KEYTOOL="
if exist "%ProgramFiles%\Android\Android Studio\jbr\bin\keytool.exe" set "KEYTOOL=%ProgramFiles%\Android\Android Studio\jbr\bin\keytool.exe"
if not defined KEYTOOL if exist "%LOCALAPPDATA%\Programs\Android Studio\jbr\bin\keytool.exe" set "KEYTOOL=%LOCALAPPDATA%\Programs\Android Studio\jbr\bin\keytool.exe"
if not defined KEYTOOL (
    where keytool >nul 2>nul
    if not errorlevel 1 set "KEYTOOL=keytool"
)
if not defined KEYTOOL (
    echo ERRO: keytool nao encontrado. Instale o Android Studio primeiro.
    pause
    exit /b 1
)

set /p SENHA=Digite a senha da keystore [minimo 6 caracteres]: 
if "%SENHA%"=="" set SENHA=rxsdrapp123

"%KEYTOOL%" -genkeypair -v -keystore "%~dp0rxsdrapp.keystore" -alias rxsdrapp -keyalg RSA -keysize 2048 -validity 10950 -storepass "%SENHA%" -keypass "%SENHA%" -dname "CN=PU1XTB, OU=RXSDR-APP, O=PU1XTB, L=Rio de Janeiro, ST=RJ, C=BR"
if errorlevel 1 (
    echo ERRO ao criar a keystore.
    pause
    exit /b 1
)

(
echo storeFile=rxsdrapp.keystore
echo storePassword=%SENHA%
echo keyAlias=rxsdrapp
echo keyPassword=%SENHA%
) > "%~dp0keystore.properties"

echo.
echo ============================================
echo  Keystore criada: rxsdrapp.keystore
echo  Config salva em: keystore.properties
echo  GUARDE ESTES DOIS ARQUIVOS COM SEGURANCA!
echo  Sem eles nao e possivel atualizar o app na Play Store.
echo ============================================
pause
