@echo off
rem Installs MiracleLoader into a Prism Launcher instance, with the example mods as a smoke test.
rem   prism-install.cmd "My Instance"               install
rem   prism-install.cmd --uninstall "My Instance"   remove it again
rem   prism-install.cmd --list                      show instances
rem Close Prism first. The work is done by "miracle consecrate"; see prism-install.sh.
setlocal
cd /d "%~dp0"
if not exist build\title-mod.jar (
    call build.cmd
    if errorlevel 1 exit /b 1
)
call tools\find-java.cmd
if errorlevel 1 exit /b 1
set "EXTRA=--examples"
if "%~1"=="--list" set "EXTRA="
if "%~1"=="--uninstall" set "EXTRA="
"%JAVA%" -jar build\miracle.jar consecrate %EXTRA% %*
exit /b %errorlevel%
