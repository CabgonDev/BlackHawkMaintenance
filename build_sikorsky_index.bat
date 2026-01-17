@echo off
REM ====================================================
REM Construye índice FTS para paquete Sikorsky (BlackHawk)
REM Requiere: Python 3 y PyMuPDF (pip install pymupdf==1.24.9)
REM ====================================================

setlocal
cd /d "%~dp0"

echo [INFO] Generando índice FTS para Sikorsky...

python build_fts_index.py ^
  --pdf_dir "app/src/main/assets/sikorsky/manuals" ^
  --asset_prefix "sikorsky/manuals" ^
  --out_db "app/src/main/assets/sikorsky/index/blackhawk_sikorsky_fts.db"

if %errorlevel% neq 0 (
    echo [ERROR] Falló la generación del índice Sikorsky.
    pause
    exit /b 1
)

echo [OK] Base de datos creada correctamente en: app/src/main/assets/sikorsky/index/
pause
