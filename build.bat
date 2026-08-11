@echo off
echo ==========================================
echo  ADB Debloater - Build Script
echo ==========================================
echo.

REM Verifica che javac sia nel PATH
where javac >nul 2>nul
if %errorlevel% neq 0 (
    echo ERRORE: javac non trovato. Installa JDK 17+ e aggiungilo al PATH.
    pause
    exit /b 1
)

REM Crea directory temporanea di build
if not exist build mkdir build

REM Compila
echo Compilazione in corso...
javac --release 17 -d build -encoding UTF-8 Main.java
if %errorlevel% neq 0 (
    echo.
    echo ERRORE durante la compilazione.
    rmdir /s /q build
    pause
    exit /b 1
)

REM Crea il file MANIFEST.MF necessario per il JAR eseguibile
echo Creazione Manifest...
echo Manifest-Version: 1.0> build\MANIFEST.MF
echo Main-Class: it.debloater.Main>> build\MANIFEST.MF
echo.>> build\MANIFEST.MF

REM Crea JAR
echo Creazione JAR...
cd build
jar cfm ..\ADBDebloater.jar MANIFEST.MF .
if %errorlevel% neq 0 (
    echo ERRORE durante la creazione del JAR.
    cd ..
    rmdir /s /q build
    pause
    exit /b 1
)
cd ..

REM Pulizia della cartella temporanea build
echo Pulizia file temporanei...
rmdir /s /q build

echo.
echo ==========================================
echo  BUILD COMPLETATO!
echo ==========================================
echo JAR creato: ADBDebloater.jar
echo.
echo Per eseguire:
echo    java -jar ADBDebloater.jar
echo.
pause