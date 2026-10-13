@echo off
rem PingPlanet launcher for Windows.
rem Builds the jar with Maven on the first run, then runs it.
rem
rem Usage: pingplanet.bat [--build] [pingplanet arguments]   (pingplanet.bat --help lists them)
rem   --build   rebuild the jar before starting (after pulling new code, for example)

setlocal
set "DIR=%~dp0"
if "%DIR:~-1%"=="\" set "DIR=%DIR:~0,-1%"
set "MIN_JAVA=21"

rem ---- arguments ---------------------------------------------------------
set "BUILD="
set "ARGS="
:parse
if "%~1"=="" goto parsed
if /i "%~1"=="--build" (
    set "BUILD=1"
) else (
    call set ARGS=%%ARGS%% "%~1"
)
shift
goto parse
:parsed

rem ---- Java: prefer JAVA_HOME, fall back to the PATH --------------------
set "JAVA=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"

"%JAVA%" -version >nul 2>&1
if errorlevel 1 (
    echo pingplanet: Java not found. Install Java %MIN_JAVA% or newer ^(e.g. https://adoptium.net^) or set JAVA_HOME.
    goto fail
)

rem First line looks like: java version "25.0.4" ...  or  openjdk version "21.0.2" ...
set "JVER="
set "VERFILE=%TEMP%\pingplanet-java-version-%RANDOM%.txt"
"%JAVA%" -version 2> "%VERFILE%"
for /f "usebackq tokens=3" %%v in ("%VERFILE%") do if not defined JVER set "JVER=%%~v"
del "%VERFILE%" >nul 2>&1
set "JMAJOR="
for /f "tokens=1,2 delims=." %%a in ("%JVER%") do (
    if "%%a"=="1" (set "JMAJOR=%%b") else (set "JMAJOR=%%a")
)
if not defined JMAJOR (
    echo pingplanet: could not read the Java version.
    goto fail
)
if %JMAJOR% LSS %MIN_JAVA% (
    echo pingplanet: Java %MIN_JAVA% or newer is required, but found Java %JVER%.
    goto fail
)

rem ---- build if needed ---------------------------------------------------
call :findjar
if defined BUILD goto build
if not defined JAR goto build
goto run

:build
where mvn >nul 2>&1
if errorlevel 1 (
    echo pingplanet: Maven ^(mvn^) is needed to build PingPlanet. Install it from https://maven.apache.org
    goto fail
)
echo Building PingPlanet...
call mvn -q -B -f "%DIR%\pom.xml" package
if errorlevel 1 (
    echo pingplanet: build failed.
    goto fail
)
call :findjar
if not defined JAR (
    echo pingplanet: build finished but no jar was found in %DIR%\target.
    goto fail
)

rem ---- run in this console -----------------------------------------------
:run
"%JAVA%" -jar "%JAR%"%ARGS%
set "CODE=%ERRORLEVEL%"
echo %CMDCMDLINE% | find /i "/c" >nul && pause
endlocal & exit /b %CODE%

:findjar
set "JAR="
for %%f in ("%DIR%\target\pingplanet-*.jar") do set "JAR=%%~ff"
exit /b 0

:fail
rem Keep the window open when started by double-click so the message can be read.
echo %CMDCMDLINE% | find /i "/c" >nul && pause
endlocal
exit /b 1
