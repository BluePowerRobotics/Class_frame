@echo off
%~dp0installer.exe  /passive TargetDir=D:\python
start /wait %~dp0makeLink.bat
%~dp0python\py.exe %~dp0setClass.py
%~dp0python\py.exe %~dp0edit.py
start %~dp0python\pyw.exe %~dp0class_frame.py
exit