@echo off
chcp 65001 >nul 2>&1
setlocal enabledelayedexpansion
title XLMod 授权更新 V1.2
set "VER=1.2"

rem ============================================================
rem  XLMod 授权一键更新脚本 V1.2
rem
rem  用法：
rem    update_license.bat            签名 打包 校验 推送 远程复验
rem    update_license.bat edit       先编辑 features.json 再执行
rem    update_license.bat nopush     只做本地签名 打包 校验
rem
rem  脚本自动定位 python 与 openssl；私钥可放 keys\ 或上级 keys\。
rem  流程用标签跳转实现，避免 cmd 括号块的老坑。
rem ============================================================

set "WS=%~dp0"
cd /d "%WS%"
set "EDIT=0"
set "PUSH=1"
if /i "%~1"=="edit"   set "EDIT=1"
if /i "%~1"=="nopush" set "PUSH=0"
if /i "%~2"=="edit"   set "EDIT=1"
if /i "%~2"=="nopush" set "PUSH=0"

set "REPO=%WS%"
if exist "%WS%tmp-repo\features.json" set "REPO=%WS%tmp-repo"

echo ============================================================
echo  XLMod 授权更新 V%VER%
echo   脚本目录 : %WS%
echo   仓库目录 : %REPO%
echo   是否推送 : %PUSH%   1 推送   0 不推送
echo ============================================================
echo.

rem ---------- 1 python ----------
set "PY="
where python >nul 2>&1 && set "PY=python"
if not defined PY where py >nul 2>&1 && set "PY=py -3"
if not defined PY if exist "C:\Python314\python.exe" set "PY=C:\Python314\python.exe"
if not defined PY if exist "%LOCALAPPDATA%\Programs\Python\Python312\python.exe" set "PY=%LOCALAPPDATA%\Programs\Python\Python312\python.exe"
if not defined PY goto :no_python
echo [1/7] python 就绪 : %PY%

rem ---------- 2 openssl ----------
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
echo [2/7] openssl 就绪 : %OPENSSL%

rem ---------- 3 私钥与配置 ----------
set "KEYDIR="
if exist "%WS%keys\xlmod_config_ec_private.pem" set "KEYDIR=%WS%keys"
if not defined KEYDIR if exist "%WS%..\keys\xlmod_config_ec_private.pem" set "KEYDIR=%WS%..\keys"
if not defined KEYDIR if exist "%WS%..\..\keys\xlmod_config_ec_private.pem" set "KEYDIR=%WS%..\..\keys"
if not defined KEYDIR goto :no_key
if not exist "%REPO%\features.json" goto :no_config
echo [3/7] 私钥就位 : %KEYDIR%

rem ---------- 4 编辑 ----------
if "%EDIT%"=="1" goto :do_edit
echo [4/7] 跳过编辑。需要编辑请运行: update_license.bat edit
goto :sign
:do_edit
echo [4/7] 打开记事本编辑配置，保存并关闭窗口后继续...
start /wait notepad "%REPO%\features.json"

rem ---------- 5 签名 打包 校验 ----------
:sign
echo [5/7] 签名并生成 license.json ...
pushd "%REPO%"
%PY% "%WS%sign_config.py" sign features.json --skip-if-same
if errorlevel 1 goto :sign_fail
%PY% "%WS%sign_config.py" verify features.json
if errorlevel 1 goto :sign_fail
%PY% "%WS%sign_config.py" bundle features.json
if errorlevel 1 goto :sign_fail
%PY% "%WS%sign_config.py" check-bundle license.json
if errorlevel 1 goto :sign_fail
popd
if not exist "%WS%xlmod-config\" goto :push
copy /y "%REPO%\features.json"         "%WS%xlmod-config\features.json"         >nul
copy /y "%REPO%\features.json.sig"     "%WS%xlmod-config\features.json.sig"     >nul
copy /y "%REPO%\features.json.sha256"  "%WS%xlmod-config\features.json.sha256"  >nul
copy /y "%REPO%\license.json"          "%WS%xlmod-config\license.json"          >nul

rem ---------- 6 提交与推送 ----------
:push
if not "%PUSH%"=="1" goto :skip_push
echo [6/7] 提交并推送 ...
pushd "%REPO%"
git add -A
git diff --cached --quiet
if not errorlevel 1 goto :no_new_commit
git -c user.name="XLMod" -c user.email="xlmod@users.noreply.github.com" commit -m "授权配置更新"
if errorlevel 1 goto :commit_fail
goto :check_remote
:no_new_commit
echo       没有新改动需要提交。
:check_remote
for /f %%H in ('git rev-parse HEAD') do set "LOCALHEAD=%%H"
set "REMOTEHEAD="
for /f "tokens=1" %%H in ('git -c http.proxy= -c https.proxy= ls-remote --heads origin main 2>nul') do set "REMOTEHEAD=%%H"
if "!REMOTEHEAD!"=="!LOCALHEAD!" goto :remote_ok
echo       正在推送 ...
git push origin main
if errorlevel 1 echo       直连失败，改为绕过本地代理重试 ...
if errorlevel 1 git -c http.proxy= -c https.proxy= push origin main
set "REMOTEHEAD="
for /f "tokens=1" %%H in ('git -c http.proxy= -c https.proxy= ls-remote --heads origin main 2>nul') do set "REMOTEHEAD=%%H"
if "!REMOTEHEAD!"=="!LOCALHEAD!" goto :push_ok
echo       [重要] 推送没成功：GitHub 暂时连不上。本地提交已完成，不会丢。
echo              稍后重跑本脚本即可，或在 %REPO% 里执行 git push origin main
popd
goto :verify_remote
:push_ok
echo       推送成功。
popd
goto :verify_remote
:remote_ok
echo       远端已是最新，无需推送。
popd
goto :verify_remote
:commit_fail
popd
echo [错误] 提交失败
goto :fail
:sign_fail
popd
echo [错误] 签名或校验失败
goto :fail

rem ---------- 7 远程复验 ----------
:skip_push
echo [6/7] 跳过推送。
:verify_remote
echo [7/7] 远程复验 ...
set "TMPJ=%TEMP%\xlmod_license_check_%RANDOM%.json"
curl -sS -m 60 -o "%TMPJ%" "https://raw.githubusercontent.com/wg-1337/xlmod/main/license.json"
if not exist "%TMPJ%" curl -sS -m 60 --noproxy "*" -o "%TMPJ%" "https://raw.githubusercontent.com/wg-1337/xlmod/main/license.json"
if not exist "%TMPJ%" goto :remote_dl_fail
pushd "%REPO%"
%PY% "%WS%sign_config.py" check-bundle "%TMPJ%"
if errorlevel 1 goto :remote_bad
popd
%PY% "%WS%show_license.py" "%TMPJ%"
%PY% "%WS%compare_license.py" "%REPO%\license.json" "%TMPJ%"
del "%TMPJ%" >nul 2>&1
goto :done_ok
:remote_bad
popd
echo [警告] 远端验签未通过：可能是 CDN 缓存未刷新，端上会保持锁定，稍后重跑即可。
del "%TMPJ%" >nul 2>&1
goto :done
:remote_dl_fail
echo [警告] 远端下载失败，稍后可重跑本脚本复验。
goto :done
:done_ok
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
:no_key
echo [错误] 找不到签名私钥 xlmod_config_ec_private.pem。
echo        请放到 %WS%keys\ 或上级 keys\ 目录里（私钥不要提交到仓库）。
goto :fail
:no_config
echo [错误] %REPO%\features.json 不存在，请确认目录结构。
goto :fail

:fail
echo.
echo ============== 失败，未完成 ==============
echo  把上面的报错截图发我即可。

:done
echo.
pause
endlocal
