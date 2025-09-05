echo stop > %~dp0stop_signal.txt
start /wait %~dp0python\pythonw.exe %~dp0edit.py 
start %~dp0python\pythonw.exe %~dp0class_frame.py
exit