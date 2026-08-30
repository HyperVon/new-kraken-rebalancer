@echo off
rem Batch script to run Kraken Rebalancer on Windows

rem Ensure we run from the script directory
cd /d "%~dp0"

rem Check if JAR exists, if not build it
set JAR_PATH=backend\build\libs\kraken-bot-0.0.1-SNAPSHOT-all.jar
if not exist "%JAR_PATH%" (
    echo Executable JAR not found. Building with Gradle...
    call gradlew.bat :backend:fatJar
    if errorlevel 1 (
        echo Gradle build failed. Exiting.
        pause
        exit /b 1
    )
)

echo Starting Kraken Rebalancer...
java -Xshare:off --sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED -jar "%JAR_PATH%"
pause
