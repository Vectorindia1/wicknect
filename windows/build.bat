@echo off
REM Builds dist\HostelAutoLogin.exe (single windowed exe). Run from the windows\ folder.
setlocal
cd /d "%~dp0"

where python >nul 2>nul || (echo [ERROR] Python 3.11+ not found. Install from https://www.python.org/downloads/ and tick "Add to PATH". & exit /b 1)
python -c "import sys; sys.exit(0 if sys.version_info>=(3,11) else 1)" || (echo [ERROR] Python 3.11 or newer is required. & exit /b 1)

if not exist .venv ( python -m venv .venv || exit /b 1 )
call .venv\Scripts\activate.bat
python -m pip install --upgrade pip >nul
python -m pip install -r requirements-dev.txt || (echo [ERROR] pip install failed. & exit /b 1)

echo Running tests...
python -m pytest -q || (echo [ERROR] Tests failed; not building. & exit /b 1)

python -m PyInstaller --noconfirm --clean --onefile --noconsole --name HostelAutoLogin ^
  --add-data "portal.json;." ^
  --hidden-import keyring.backends.Windows ^
  --hidden-import pystray._win32 ^
  run_hostelautologin.py || (echo [ERROR] PyInstaller failed. & exit /b 1)

echo.
echo Built: %CD%\dist\HostelAutoLogin.exe
echo Edit %%APPDATA%%\HostelAutoLogin\portal.json after the first run (tray menu: Edit portal.json).
endlocal
