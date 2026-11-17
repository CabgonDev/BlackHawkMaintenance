@echo off
REM =========================================================
REM Construye índices FTS para IADS y Sikorsky (BlackHawk)
REM =========================================================
cd /d "%~dp0"
echo [INFO] Iniciando generación de índices para BlackHawk...
python build_fts_index.py
if %errorlevel% neq 0 (
    echo [ERROR] Falló la generación.
    pause
    exit /b 1
)
echo [OK] Bases de datos generadas correctamente.
pause
