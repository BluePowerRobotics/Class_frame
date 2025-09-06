echo stop > %~dp0.stop_signal
start /wait %~dp0python\pyw.exe %~dp0edit.py 
start %~dp0python\pyw.exe %~dp0class_frame.py
exit