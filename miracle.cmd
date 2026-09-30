@echo off
rem MiracleToolChain for Windows. Put this folder on your PATH and type "miracle" anywhere
rem (cmd or PowerShell). Works from a checkout (jars in build\) and from the release zip (jars
rem right here).
setlocal
set "DIR=%~dp0"
set "JAR=%DIR%miracle.jar"
if exist "%JAR%" goto java
set "JAR=%DIR%build\miracle.jar"
if exist "%JAR%" if exist "%DIR%build\miracle-loader.jar" goto java
if not exist "%DIR%build.cmd" (
    echo miracle.jar isn't next to this script. 1>&2
    exit /b 1
)
echo First prayer: building the toolchain... 1>&2
call "%DIR%build.cmd" >nul
if errorlevel 1 exit /b 1

:java
set "JAVA=java"
if defined JAVA_HOME set "JAVA=%JAVA_HOME%\bin\java.exe"
set "SPEC=0"
set "TMPFILE=%TEMP%\miracle-java-%RANDOM%.txt"
"%JAVA%" -XshowSettings:properties -version > "%TMPFILE%" 2>&1
for /f "tokens=2 delims==" %%v in ('findstr /c:"java.specification.version" "%TMPFILE%"') do set "SPEC=%%v"
del "%TMPFILE%" >nul 2>&1
set "SPEC=%SPEC: =%"
if "%SPEC:~0,2%"=="1." set "SPEC=8"
set /a MAJOR=%SPEC% >nul 2>&1
if %MAJOR% GEQ 25 goto run
if not "%SPEC%"=="0" goto miracle_old
echo No Java was found: not in JAVA_HOME, not on the PATH. 1>&2
goto miracle_hint
:miracle_old
rem (no parentheses around this: a path like "Program Files (x86)" would end the block)
echo MiracleToolChain needs Java 25 or newer, found Java %SPEC%: %JAVA% 1>&2
:miracle_hint
echo Install one: winget install EclipseAdoptium.Temurin.25.JDK ^(or the installer from adoptium.net^), 1>&2
echo then open a new terminal. With several JDKs installed, set JAVA_HOME to the new one. 1>&2
exit /b 1

:run
"%JAVA%" -jar "%JAR%" %*
exit /b %errorlevel%
