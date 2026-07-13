@echo off
powershell -ExecutionPolicy Bypass -File "%~dp0stop-platform.ps1" %*
pause
