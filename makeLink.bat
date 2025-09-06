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

rem === 删除已有目标（可能是链接或目录） ===
if exist "%SCRIPT_DIR%python" (
    echo delete existing python dir or link…
    rd /s /q "%SCRIPT_DIR%python" 2>nul
    del "%SCRIPT_DIR%python" 2>nul
)

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

echo succeed.
