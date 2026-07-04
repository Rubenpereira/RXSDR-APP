@echo off
chcp 65001 >nul
title RXSDR-APP - Enviar para o GitHub
cd /d "%~dp0"
echo ==============================================================
echo   RXSDR-APP - Envio para github.com/Rubenpereira/RXSDR-APP
echo ==============================================================
echo.

where git >nul 2>nul
if errorlevel 1 (
    echo ERRO: Git nao encontrado no PATH. Instale o "Git for Windows".
    pause
    exit /b 1
)

if not exist ".git" git init -b main
git config user.name >nul 2>nul || git config user.name "Ruben Pereira PU1XTB"
git config user.email >nul 2>nul || git config user.email "pu1xtb@gmail.com"

git add -A
git commit -m "Atualizacao %date% %time%"
git remote remove origin >nul 2>nul
git remote add origin https://github.com/Rubenpereira/RXSDR-APP.git

echo.
echo Enviando...
git push -u origin main
if errorlevel 1 (
    echo.
    echo ERRO no envio. Causas comuns:
    echo  - Repositorio ainda nao criado em https://github.com/new
    echo  - Login cancelado no navegador
    pause
    exit /b 1
)
echo.
echo ==============================================================
echo  PRONTO! Publicado em:
echo  https://github.com/Rubenpereira/RXSDR-APP
echo ==============================================================
pause
