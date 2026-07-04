@echo off
chcp 65001 >nul
title RXSDR-APP - Enviar para o GitHub
cd /d "%~dp0"
echo ==============================================================
echo   RXSDR-APP - Envio para github.com/Rubenpereira/RXSDR-APP
echo ==============================================================
echo.
echo ANTES DA PRIMEIRA VEZ: crie o repositorio vazio no site:
echo   1. Abra  https://github.com/new
echo   2. Repository name: RXSDR-APP
echo   3. Public  ^>  botao "Create repository"  (nao marque nada mais)
echo.
pause

where git >nul 2>nul
if errorlevel 1 (
    echo ERRO: Git nao encontrado no PATH. Instale o "Git for Windows".
    pause
    exit /b 1
)

if not exist ".git" (
    git init -b main
)
git config user.name >nul 2>nul || git config user.name "Ruben Pereira PU1XTB"
git config user.email >nul 2>nul || git config user.email "pu1xtb@gmail.com"

git add -A
git commit -m "RXSDR-APP v1.0 - lancamento inicial" 
git remote remove origin >nul 2>nul
git remote add origin https://github.com/Rubenpereira/RXSDR-APP.git

echo.
echo Enviando... (na primeira vez o Git abre o navegador para voce
echo entrar na sua conta do GitHub - e so autorizar)
git push -u origin main
if errorlevel 1 (
    echo.
    echo ERRO no envio. Causas comuns:
    echo  - O repositorio ainda nao foi criado no site (passo acima)
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
