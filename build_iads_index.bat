@echo off
REM ====================================================
REM Construye índice FTS para paquete IADS (BlackHawk)
REM Requiere: Python 3 y PyMuPDF (pip install pymupdf==1.24.9)
REM ====================================================

setlocal
cd /d "%~dp0"

echo [INFO] Generando índice FTS para IADS...

python build_fts_index.py ^
  --pdf_dir "app/src/main/assets/iads/manuals" ^
  --asset_prefix "iads/manuals" ^
  --out_db "app/src/main/assets/iads/index/blackhawk_iads_fts.db"

if %errorlevel% neq 0 (
    echo [ERROR] Falló la generación del índice IADS.
    pause
    exit /b 1
)

echo [OK] Base de datos creada correctamente en: app/src/main/assets/iads/index/
pause
