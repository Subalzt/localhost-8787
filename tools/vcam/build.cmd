@echo off
rem Builds vcam.dll (the phone as the laptop's webcam) with the Visual Studio Build Tools' C++ compiler,
rem and copies it into the app's assets, where the laptop helper fetches it from the phone.
setlocal
set "VS="
for %%v in (18 2022 2019) do for %%e in (BuildTools Community Professional Enterprise) do (
  for %%r in ("%ProgramFiles(x86)%" "%ProgramFiles%") do (
    if exist "%%~r\Microsoft Visual Studio\%%v\%%e\VC\Auxiliary\Build\vcvars64.bat" set "VS=%%~r\Microsoft Visual Studio\%%v\%%e"
  )
)
if not defined VS (echo No Visual Studio C++ tools found & exit /b 1)
call "%VS%\VC\Auxiliary\Build\vcvars64.bat" >nul || exit /b 1
cd /d "%~dp0"
cl /nologo /O2 /EHsc /MT /W3 /LD /DUNICODE /D_UNICODE vcam.cpp /link /DEF:vcam.def /OUT:vcam.dll || exit /b 1
copy /y vcam.dll "%~dp0..\..\app\src\main\assets\vcam.dll" >nul
echo built vcam.dll
