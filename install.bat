@echo off
%~dp0installer.exe  /passive TargetDir=%~dp0python
if not exist "%~dp0python\python.exe" (
    start /wait %~dp0makeLink.bat
)
%~dp0python\python.exe %~dp0setClass.py
%~dp0python\python.exe %~dp0edit.py
start %~dp0python\pythonw.exe %~dp0class_frame.py
exit