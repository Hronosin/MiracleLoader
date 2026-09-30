@echo off
rem MiracleLoader build for Windows: build.cmd in cmd, .\build.cmd in PowerShell.
rem No Gradle, no Maven: javac and jar, as promised. The build itself is tools\build\Build.java,
rem the same one build.sh runs.
setlocal
cd /d "%~dp0"
call tools\find-java.cmd
if errorlevel 1 exit /b 1
"%JAVA%" tools\build\Build.java %*
exit /b %errorlevel%
