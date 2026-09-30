@echo off
rem Sets JAVA to a Java 25 or newer: JAVA_HOME's, else the one on the PATH. Says what's wrong and
rem fails otherwise. Called by build.cmd, run.cmd and prism-install.cmd; no setlocal here, so JAVA
rem reaches the caller.
set "JAVA=java"
if defined JAVA_HOME set "JAVA=%JAVA_HOME%\bin\java.exe"
set "MIRACLE_SPEC=0"
set "MIRACLE_TMP=%TEMP%\miracle-java-%RANDOM%.txt"
"%JAVA%" -XshowSettings:properties -version > "%MIRACLE_TMP%" 2>&1
for /f "tokens=2 delims==" %%v in ('findstr /c:"java.specification.version" "%MIRACLE_TMP%"') do set "MIRACLE_SPEC=%%v"
del "%MIRACLE_TMP%" >nul 2>&1
set "MIRACLE_SPEC=%MIRACLE_SPEC: =%"
if "%MIRACLE_SPEC:~0,2%"=="1." set "MIRACLE_SPEC=8"
set /a MIRACLE_MAJOR=%MIRACLE_SPEC% >nul 2>&1
if %MIRACLE_MAJOR% GEQ 25 exit /b 0
if not "%MIRACLE_SPEC%"=="0" goto miracle_old
echo No Java was found: not in JAVA_HOME, not on the PATH. 1>&2
goto miracle_hint
:miracle_old
rem (no parentheses around this: a path like "Program Files (x86)" would end the block)
echo Need a JDK 25 or newer, found Java %MIRACLE_SPEC%: %JAVA% 1>&2
:miracle_hint
echo Install one: winget install EclipseAdoptium.Temurin.25.JDK ^(or the installer from adoptium.net^), 1>&2
echo then open a new terminal. With several JDKs installed, set JAVA_HOME to the new one. 1>&2
exit /b 1
