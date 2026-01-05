@echo off
setlocal enabledelayedexpansion

echo ========================================
echo TriAlerta Standalone Builder
echo (Includes Java Runtime - No Java needed!)
echo ========================================
echo.

REM Step 1: Build the project
echo [1/4] Building project...
call mvn clean package
if errorlevel 1 (
    echo Error: Maven build failed
    pause
    exit /b 1
)
echo Build successful!
echo.

REM Step 2: Create custom JRE
echo [2/4] Creating custom Java runtime...
echo This includes only the Java modules needed by TriAlerta
echo.

set JRE_DIR=target\jre
if exist "%JRE_DIR%" rmdir /s /q "%JRE_DIR%"

REM Create minimal JRE with required modules (no JavaFX yet - we'll add it manually)
jlink --add-modules java.base,java.desktop,java.logging,java.naming,java.net.http,java.prefs,java.security.jgss,java.security.sasl,java.sql,java.xml,jdk.crypto.ec,jdk.unsupported,java.management,java.instrument --output "%JRE_DIR%" --strip-debug --no-header-files --no-man-pages --compress=2

if errorlevel 1 (
    echo Error: jlink failed
    echo Make sure JDK 17+ is installed
    pause
    exit /b 1
)
echo Custom JRE created successfully!
echo.

REM Step 3: Create distribution
echo [3/4] Creating standalone distribution...

set DIST_DIR=target\TriAlerta-Standalone
if exist "%DIST_DIR%" rmdir /s /q "%DIST_DIR%"
mkdir "%DIST_DIR%"
mkdir "%DIST_DIR%\app"

REM Copy JRE
xcopy "%JRE_DIR%" "%DIST_DIR%\jre\" /E /I /Q

REM Copy application
copy target\TriAlerta-1.0-SNAPSHOT.jar "%DIST_DIR%\app\"
xcopy target\libs "%DIST_DIR%\app\libs\" /E /I /Q

REM Copy resources
if exist "src\main\resources\Config.properties" copy "src\main\resources\Config.properties" "%DIST_DIR%\app\"
if exist "src\main\resources\mail.png" copy "src\main\resources\mail.png" "%DIST_DIR%\"

REM Create launcher script that uses bundled JRE
echo @echo off > "%DIST_DIR%\TriAlerta.bat"
echo title TriAlerta >> "%DIST_DIR%\TriAlerta.bat"
echo cd /d "%%~dp0" >> "%DIST_DIR%\TriAlerta.bat"
echo. >> "%DIST_DIR%\TriAlerta.bat"
echo REM Use bundled Java runtime >> "%DIST_DIR%\TriAlerta.bat"
echo set JAVA_CMD=jre\bin\java.exe >> "%DIST_DIR%\TriAlerta.bat"
echo. >> "%DIST_DIR%\TriAlerta.bat"
echo if not exist "%%JAVA_CMD%%" ( >> "%DIST_DIR%\TriAlerta.bat"
echo     echo Error: Java runtime not found >> "%DIST_DIR%\TriAlerta.bat"
echo     echo Please extract the full distribution >> "%DIST_DIR%\TriAlerta.bat"
echo     pause >> "%DIST_DIR%\TriAlerta.bat"
echo     exit /b 1 >> "%DIST_DIR%\TriAlerta.bat"
echo ) >> "%DIST_DIR%\TriAlerta.bat"
echo. >> "%DIST_DIR%\TriAlerta.bat"
echo REM Run application with JavaFX module path >> "%DIST_DIR%\TriAlerta.bat"
echo start "TriAlerta" "%%JAVA_CMD%%" --module-path "app\libs" --add-modules javafx.controls,javafx.fxml,javafx.graphics,javafx.swing --add-opens java.base/java.lang=ALL-UNNAMED -Dfile.encoding=UTF-8 -jar "app\TriAlerta-1.0-SNAPSHOT.jar" >> "%DIST_DIR%\TriAlerta.bat"

REM Create debug launcher
echo @echo off > "%DIST_DIR%\TriAlerta-Debug.bat"
echo title TriAlerta Debug Mode >> "%DIST_DIR%\TriAlerta-Debug.bat"
echo cd /d "%%~dp0" >> "%DIST_DIR%\TriAlerta-Debug.bat"
echo. >> "%DIST_DIR%\TriAlerta-Debug.bat"
echo echo ======================================== >> "%DIST_DIR%\TriAlerta-Debug.bat"
echo echo TriAlerta - Debug Mode >> "%DIST_DIR%\TriAlerta-Debug.bat"
echo echo ======================================== >> "%DIST_DIR%\TriAlerta-Debug.bat"
echo echo. >> "%DIST_DIR%\TriAlerta-Debug.bat"
echo. >> "%DIST_DIR%\TriAlerta-Debug.bat"
echo set JAVA_CMD=jre\bin\java.exe >> "%DIST_DIR%\TriAlerta-Debug.bat"
echo. >> "%DIST_DIR%\TriAlerta-Debug.bat"
echo if not exist "%%JAVA_CMD%%" ( >> "%DIST_DIR%\TriAlerta-Debug.bat"
echo     echo ERROR: Java runtime not found! >> "%DIST_DIR%\TriAlerta-Debug.bat"
echo     pause >> "%DIST_DIR%\TriAlerta-Debug.bat"
echo     exit /b 1 >> "%DIST_DIR%\TriAlerta-Debug.bat"
echo ) >> "%DIST_DIR%\TriAlerta-Debug.bat"
echo. >> "%DIST_DIR%\TriAlerta-Debug.bat"
echo echo Starting application with verbose logging... >> "%DIST_DIR%\TriAlerta-Debug.bat"
echo echo. >> "%DIST_DIR%\TriAlerta-Debug.bat"
echo. >> "%DIST_DIR%\TriAlerta-Debug.bat"
echo "%%JAVA_CMD%%" --module-path "app\libs" --add-modules javafx.controls,javafx.fxml,javafx.graphics,javafx.swing --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED -Dfile.encoding=UTF-8 -jar "app\TriAlerta-1.0-SNAPSHOT.jar" >> "%DIST_DIR%\TriAlerta-Debug.bat"
echo. >> "%DIST_DIR%\TriAlerta-Debug.bat"
echo echo. >> "%DIST_DIR%\TriAlerta-Debug.bat"
echo echo Exit Code: %%ERRORLEVEL%% >> "%DIST_DIR%\TriAlerta-Debug.bat"
echo pause >> "%DIST_DIR%\TriAlerta-Debug.bat"

REM Create README
echo TriAlerta - Standalone Version > "%DIST_DIR%\README.txt"
echo ================================ >> "%DIST_DIR%\README.txt"
echo. >> "%DIST_DIR%\README.txt"
echo This is a FULLY STANDALONE version! >> "%DIST_DIR%\README.txt"
echo Java is included - NO installation needed! >> "%DIST_DIR%\README.txt"
echo. >> "%DIST_DIR%\README.txt"
echo To run TriAlerta: >> "%DIST_DIR%\README.txt"
echo   Simply double-click TriAlerta.bat >> "%DIST_DIR%\README.txt"
echo. >> "%DIST_DIR%\README.txt"
echo If the application closes immediately: >> "%DIST_DIR%\README.txt"
echo   Run TriAlerta-Debug.bat to see error messages >> "%DIST_DIR%\README.txt"
echo. >> "%DIST_DIR%\README.txt"
echo This portable version can be: >> "%DIST_DIR%\README.txt"
echo   - Copied to any Windows computer >> "%DIST_DIR%\README.txt"
echo   - Run from a USB drive >> "%DIST_DIR%\README.txt"
echo   - Moved to any folder >> "%DIST_DIR%\README.txt"
echo. >> "%DIST_DIR%\README.txt"
echo No installation required! >> "%DIST_DIR%\README.txt"

REM Step 4: Create ZIP file
echo [4/4] Creating ZIP archive...

set ZIP_FILE=target\TriAlerta-Standalone-Windows.zip
if exist "%ZIP_FILE%" del "%ZIP_FILE%"

REM Use PowerShell to create zip
powershell -command "Compress-Archive -Path '%DIST_DIR%\*' -DestinationPath '%ZIP_FILE%'"

if errorlevel 1 (
    echo Warning: Could not create ZIP file
    echo You can manually zip the folder: %DIST_DIR%
) else (
    echo ZIP created successfully!
)

echo.
echo ========================================
echo SUCCESS!
echo ========================================
echo.
echo Standalone version created:
echo   Folder: %DIST_DIR%
echo   ZIP: %ZIP_FILE%
echo   Size: ~60-80 MB (includes Java runtime)
echo.
echo Contents:
echo   - jre\ (bundled Java runtime)
echo   - app\ (TriAlerta application + dependencies)
echo   - TriAlerta.bat (launcher)
echo   - README.txt
echo.
echo To distribute:
echo   Share the ZIP file: %ZIP_FILE%
echo.
echo Users just need to:
echo   1. Extract the ZIP
echo   2. Double-click TriAlerta.bat
echo   3. Done! NO Java installation needed!
echo.
pause