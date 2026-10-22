@echo off
rem Запуск лаунчера без консольного окна.
cd /d "%~dp0"
start "" ".venv\Scripts\pythonw.exe" -m l2hub %*
