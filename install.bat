%~dp0installer.exe  /passive TargetDir=D:\python
D:\python\python.exe -m venv %~dp0python
%~dp0edit.bat
%~dp0setClass.bat
exit
