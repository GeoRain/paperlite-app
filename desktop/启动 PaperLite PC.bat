@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

rem PaperLite PC 启动器：只依赖 Windows 自带的命令与系统 Python。
rem 找不到 Python 时给出明确的安装提示，而不是闪一下就没了。

set "PY="
where py >nul 2>nul && set "PY=py -3"
if not defined PY (
    where python >nul 2>nul && set "PY=python"
)
if not defined PY (
    echo.
    echo   没有找到 Python。
    echo   这个辅助工具用的是系统自带的 Python 标准库，不需要装任何第三方包。
    echo   装好 Python 3.9 或更新版本后，再双击本文件即可。
    echo   下载：https://www.python.org/downloads/
    echo.
    pause
    exit /b 1
)

echo 正在启动 PaperLite PC...
%PY% "%~dp0paperlite_pc.py"
if errorlevel 1 (
    echo.
    echo 启动失败，上面是错误信息。
    pause
)
