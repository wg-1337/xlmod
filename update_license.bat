@echo off
chcp 65001 >nul 2>&1
setlocal enabledelayedexpansion
title XLMod 授权更新 V2.0（加密授权）

rem ============================================================
rem  XLMod 授权一键更新 V2.0（对应 Mod V4.3p 加密授权）
rem
rem  用法：
rem    update_license.bat            加密 签名 校验 推送 远程复验
rem    update_license.bat edit       先编辑 features.json 再执行
rem    update_license.bat nopush     只做本地加密 校验
rem
rem  与 V1.x 的区别：仓库里只保留密文 license.json，
rem  明文的 features.json 会从仓库删除（只在作者本地保留）。
rem  真正的流程在 publish_license.py 里，本脚本只负责找 python 与 openssl。
rem ============================================================

set "WS=%~dp0"
cd /d "%WS%"
set "EXTRA="
if /i "%~1"=="edit"   set "EXTRA=--edit"
if /i "%~1"=="nopush" set "EXTRA=--nopush"
if /i "%~2"=="edit"   set "EXTRA=--edit"
if /i "%~2"=="nopush" set "EXTRA=--nopush"

echo ============================================================
echo  XLMod 授权更新 V2.0（加密授权）
echo   脚本目录 : %WS%
echo   参数     : %EXTRA%
echo ============================================================
echo.

set "PY="
where python >nul 2>&1 && set "PY=python"
if not defined PY where py >nul 2>&1 && set "PY=py -3"
if not defined PY if exist "C:\Python314\python.exe" set "PY=C:\Python314\python.exe"
if not defined PY if exist "%LOCALAPPDATA%\Programs\Python\Python312\python.exe" set "PY=%LOCALAPPDATA%\Programs\Python\Python312\python.exe"
if not defined PY goto :no_python
echo [1/3] python 就绪 : %PY%

set "OPENSSL="
where openssl >nul 2>&1 && set "OPENSSL=openssl"
if not defined OPENSSL if exist "%ProgramFiles%\Git\usr\bin\openssl.exe" set "OPENSSL=%ProgramFiles%\Git\usr\bin\openssl.exe"
if not defined OPENSSL if exist "%ProgramFiles%\Git\mingw64\bin\openssl.exe" set "OPENSSL=%ProgramFiles%\Git\mingw64\bin\openssl.exe"
if not defined OPENSSL if exist "%ProgramFiles(x86)%\Git\usr\bin\openssl.exe" set "OPENSSL=%ProgramFiles(x86)%\Git\usr\bin\openssl.exe"
if not defined OPENSSL if exist "%LOCALAPPDATA%\Programs\Git\usr\bin\openssl.exe" set "OPENSSL=%LOCALAPPDATA%\Programs\Git\usr\bin\openssl.exe"
if not defined OPENSSL if exist "C:\OpenSSL-Win64\bin\openssl.exe" set "OPENSSL=C:\OpenSSL-Win64\bin\openssl.exe"
if not defined OPENSSL if defined XLMod_OPENSSL set "OPENSSL=%XLMod_OPENSSL%"
if not defined OPENSSL goto :no_openssl
set "XLMod_OPENSSL=%OPENSSL%"
echo [2/3] openssl 就绪 : %OPENSSL%

echo [3/3] 执行发布流程 ...
%PY% "%WS%publish_license.py" %EXTRA%
if errorlevel 1 goto :fail
echo.
echo ================== 完成 ==================
echo  端上地址 https://raw.githubusercontent.com/wg-1337/xlmod/main/license.json
echo  生效时间 端上最长 60 秒自动校验，也可在面板点 立即校验授权
echo  仓库页面 https://github.com/wg-1337/xlmod
goto :done

:no_python
echo [错误] 找不到 python。请安装 Python 3 并勾选 Add to PATH。
goto :fail
:no_openssl
echo [错误] 找不到 openssl。最省事：安装 Git for Windows（自带 openssl）；
echo        或设置环境变量 XLMod_OPENSSL 指向 openssl.exe。
goto :fail

:fail
echo.
echo ============== 失败，未完成 ==============
echo  把上面的报错截图发我即可。

:done
echo.
pause
endlocal
