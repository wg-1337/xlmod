@echo off
chcp 65001 >nul 2>&1
setlocal enabledelayedexpansion
title XLMod 授权配置更新（签名 + 打包 + 推送）

rem ============================================================
rem  XLMod 授权一键更新脚本
rem    update_license.bat              正常流程：签名 -> 打包 -> 校验 -> 推送 -> 远程复验
rem    update_license.bat edit         先打开记事本编辑 features.json，再走上面的流程
rem    update_license.bat nopush       只做本地签名/打包/校验，不推送
rem    update_license.bat edit nopush  编辑 + 本地校验（不推送）
rem  目录约定：脚本放在项目根目录（与 keys\ 同级）；若存在 tmp-repo\（GitHub 克隆），则作为推送目标仓库。
rem ============================================================

set "WS=%~dp0"
cd /d "%WS%"

set "EDIT=0"
set "PUSH=1"
for %%A in (%*) do (
    if /i "%%~A"=="edit"   set "EDIT=1"
    if /i "%%~A"=="nopush" set "PUSH=0"
)

if exist "%WS%tmp-repo\features.json" (
    set "REPO=%WS%tmp-repo"
) else (
    set "REPO=%WS%"
)

echo ============================================================
echo  XLMod 授权更新
echo   工作目录 : %WS%
echo   仓库目录 : %REPO%
echo   是否推送 : %PUSH%   （1=推送  0=不推送）
echo ============================================================
echo.

where python >nul 2>&1 || (echo [错误] 找不到 python，请先安装并加入 PATH & goto :fail)
where openssl >nul 2>&1 || (echo [错误] 找不到 openssl，请先安装并加入 PATH & goto :fail)
where git >nul 2>&1 || (echo [错误] 找不到 git，请先安装并加入 PATH & goto :fail)
echo [1/7] 环境检查通过（python / openssl / git）

if not exist "%REPO%\features.json" (
    echo [错误] %REPO%\features.json 不存在，请确认目录结构
    goto :fail
)
if not exist "%WS%keys\xlmod_config_ec_private.pem" (
    if not exist "%REPO%\keys\xlmod_config_ec_private.pem" (
        echo [错误] 找不到签名私钥 xlmod_config_ec_private.pem
        echo        请放在 %WS%keys\ 或 %REPO%\keys\ 下（私钥不要提交到仓库）
        goto :fail
    )
)
echo [2/7] 私钥就位

if "%EDIT%"=="1" (
    echo [3/7] 打开记事本编辑配置（保存并关闭窗口后继续）...
    start /wait notepad "%REPO%\features.json"
) else (
    echo [3/7] 跳过编辑（如需编辑：update_license.bat edit）
)

echo [4/7] 签名配置...
pushd "%REPO%"
python "%WS%sign_config.py" sign features.json --skip-if-same
if errorlevel 1 ( popd & echo [错误] 签名失败 & goto :fail )
python "%WS%sign_config.py" verify features.json
if errorlevel 1 ( popd & echo [错误] 验签失败 & goto :fail )

echo [5/7] 生成 license.json（配置+签名单文件）并校验...
python "%WS%sign_config.py" bundle features.json
if errorlevel 1 ( popd & echo [错误] 打包失败 & goto :fail )
python "%WS%sign_config.py" check-bundle license.json
if errorlevel 1 ( popd & echo [错误] license.json 校验失败 & goto :fail )
popd

if exist "%WS%xlmod-config\" (
    copy /y "%REPO%\features.json"         "%WS%xlmod-config\features.json"         >nul
    copy /y "%REPO%\features.json.sig"     "%WS%xlmod-config\features.json.sig"     >nul
    copy /y "%REPO%\features.json.sha256"  "%WS%xlmod-config\features.json.sha256"  >nul 2>&1
    copy /y "%REPO%\license.json"          "%WS%xlmod-config\license.json"          >nul 2>&1
)

if "%PUSH%"=="1" (
    echo [6/7] 提交并推送...
    pushd "%REPO%"
    git add features.json features.json.sig features.json.sha256 license.json
    git diff --cached --quiet
    if errorlevel 1 (
        git -c user.name="XLMod" -c user.email="xlmod@users.noreply.github.com" commit -m "授权配置更新"
        if errorlevel 1 ( popd & echo [错误] 提交失败 & goto :fail )
        git push origin main
        if errorlevel 1 (
            echo       推送失败，尝试绕过本地代理重试...
            git -c http.proxy= -c https.proxy= push origin main
        )
        if errorlevel 1 (
            echo [警告] 推送返回非零（本环境常见：远端其实已更新）
            echo        下面用远端实际内容复验，能验通就说明已生效。
        )
    ) else (
        echo       没有变化，无需提交（配置与上次一致）
    )
    popd
) else (
    echo [6/7] 跳过推送（nopush）
)

echo [7/7] 远程复验（抓取远端 license.json 并验签）...
set "TMPJ=%TEMP%\xlmod_license_check_%RANDOM%.json"
curl -sS -m 60 -o "%TMPJ%" "https://raw.githubusercontent.com/wg-1337/xlmod/main/license.json?cb=%RANDOM%"
if errorlevel 1 (
    curl -sS -m 60 --noproxy "*" -o "%TMPJ%" "https://raw.githubusercontent.com/wg-1337/xlmod/main/license.json?cb=%RANDOM%"
)
if errorlevel 1 (
    echo [警告] 下载失败（网络问题？）。稍后可重跑本脚本复验。
    goto :done
)
pushd "%REPO%"
python "%WS%sign_config.py" check-bundle "%TMPJ%"
if errorlevel 1 (
    popd
    echo [警告] 远端验签未通过：可能是 CDN 缓存还没刷新（一般 5 分钟内自愈）。
    echo        端上会保持锁定，稍后重跑本脚本即可复验。
    del "%TMPJ%" >nul 2>&1
    goto :done
)
popd
python "%WS%show_license.py" "%TMPJ%"
python "%WS%compare_license.py" "%REPO%\license.json" "%TMPJ%"
del "%TMPJ%" >nul 2>&1
echo.
echo ================== 完成 ==================
echo  端上地址：https://raw.githubusercontent.com/wg-1337/xlmod/main/license.json
echo  生效时间：端上最长 60 秒自动校验（也可在面板点「立即校验授权」）
echo  仓库页面：https://github.com/wg-1337/xlmod/blob/main/license.json
goto :done

:fail
echo.
echo ============== 失败，未完成 ==============
echo  把上面的报错信息截图发我即可。

:done
echo.
pause
endlocal
