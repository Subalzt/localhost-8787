@echo off
rem Word timing for songs on the phone not yet timed by the word (see align.py).
rem   align.bat                 the phone over USB (plugged in, USB debugging on)
rem   align.bat 192.168.1.14    the phone over Wi-Fi, at that address
rem Anything after the address goes to align.py, e.g. align.bat usb --ids 151,206 --redo
setlocal
cd /d "%~dp0"

if not exist venv\Scripts\python.exe (
  echo Setting up the aligner in tools\lyrics-align\venv, once: about 3 GB, a few minutes.
  py -3.11 -m venv venv || python -m venv venv || goto :nopython
  venv\Scripts\python -m pip install --upgrade pip >nul
  venv\Scripts\python -m pip install -r requirements.txt || goto :failed
)
where ffmpeg >nul 2>nul || (echo ffmpeg is needed on PATH: winget install Gyan.FFmpeg & exit /b 1)

set "TARGET=%~1"
if "%TARGET%"=="" set "TARGET=usb"
if /i "%TARGET%"=="usb" (
  set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
  if not exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" set "ADB=adb"
  call "%%ADB%%" forward tcp:8788 tcp:8787 >nul || (echo The phone is not on USB, or USB debugging is off. & exit /b 1)
  set "PHONE=127.0.0.1:8788"
) else (
  set "PHONE=%TARGET%:8787"
)
set "REST="
shift
:args
if "%~1"=="" goto :run
set "REST=%REST% %1"
shift
goto :args

:run
set PYTHONIOENCODING=utf-8
venv\Scripts\python -W ignore -u align.py --phone %PHONE% %REST%
exit /b %errorlevel%

:nopython
echo Python 3.11 is needed: winget install Python.Python.3.11
exit /b 1
:failed
echo Setting up failed; delete tools\lyrics-align\venv and try again.
exit /b 1
