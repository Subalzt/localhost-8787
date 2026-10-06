@echo off
setlocal
call "%ProgramFiles(x86)%\Microsoft Visual Studio\18\BuildTools\VC\Auxiliary\Build\vcvars64.bat" >nul 2>nul
cd /d "%~dp0"
cl /nologo /O2 /EHsc /W3 /DUNICODE /D_UNICODE camtest.cpp /link /OUT:camtest.exe || exit /b 1
camtest.exe %1
