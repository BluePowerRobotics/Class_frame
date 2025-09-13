@echo off
chcp 65001


rem === 权限提升部分 ===
FSUTIL DIRTY query %SystemDrive% >NUL 2>&1
if errorlevel 1 (
    echo 正在提升权限…
    powershell -Command "Start-Process -FilePath '%~f0' -ArgumentList 'elevated' -Verb RunAs"
    timeout /t 10
    exit
)
if "%1" neq "elevated" (
    echo 正在提升权限…
    powershell -Command "Start-Process '%~f0' -ArgumentList 'elevated' -Verb RunAs"
    timeout /t 10
    exit
)
:Elevated
echo 已获得管理员权限。

rem === 获取 Python 可执行路径 ===
for /f "delims=" %%A in ('where py 2^>nul') do (
    set "PY_EXE=%%A"
    rem 这里使用 %%~dpA 拿到 Python 执行文件所在目录（包含斜杠）
    set "PY_DIR=%%~dpA"
)

if not defined PY_EXE (
    echo 未找到 py.exe，请确认 Python 已安装并加入 PATH。
    exit /b 1
)

echo python install dir：%PY_DIR%

rem === 当前脚本所在目录 ===
set "SCRIPT_DIR=%~dp0"
echo script dir: %SCRIPT_DIR%

@REM rem === 删除已有目标（可能是链接或目录） ===
@REM if exist "%SCRIPT_DIR%python" (
@REM     echo delete existing python dir or link…
@REM     rd /s /q "%SCRIPT_DIR%python" 2>nul
@REM     del "%SCRIPT_DIR%python" 2>nul
@REM )

rem === 尝试创建符号链接 ===
mklink /D "%SCRIPT_DIR%python" "%PY_DIR%" >nul 2>&1
if errorlevel 1 (
    echo mklink create link failed. try copy...
    rem 优先使用 robocopy
    robocopy "%PY_DIR%" "%SCRIPT_DIR%python" /MIR /NFL /NDL /NJH /NJS >nul 2>&1
    if errorlevel 1 (
        echo robocopy copy failed. try xcopy...
        xcopy "%PY_DIR%\*" "%SCRIPT_DIR%python\" /E /I /Y >nul 2>&1
        if errorlevel 1 (
            echo ERROR:copy dir failed, check permission or space。
            exit /b 1
        ) else (
            echo use xcopy copy Python dir successfully。
        )
    ) else (
        echo use robocopy copy Python dir successfully。
    )
) else (
    echo create 'python' link successfully -> %PY_DIR%
)
rem === try to copy py.exe in python dir to python.exe ===
if exist "%SCRIPT_DIR%python\py.exe" (
    echo copy py.exe to python.exe...
    copy "%SCRIPT_DIR%python\py.exe" "%SCRIPT_DIR%python\python.exe" /Y >nul 2>&1
    copy "%SCRIPT_DIR%python\pyw.exe" "%SCRIPT_DIR%python\pythonw.exe" /Y >nul 2>&1
    if errorlevel 1 (
        echo ERROR:copy py.exe to python.exe failed.
        exit /b 1
    )
) else (
    echo py.exe not found in python dir.
)
echo succeed.
