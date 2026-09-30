@echo off
rem Runs the fake game through MiracleLoader with the example mods (run.sh, for Windows).
setlocal
cd /d "%~dp0"
if not exist build\miracle-loader.jar (
    call build.cmd
    if errorlevel 1 exit /b 1
)
call tools\find-java.cmd
if errorlevel 1 exit /b 1
if exist build\run rmdir /s /q build\run
mkdir build\run\mods
copy /y build\mods\*.jar build\run\mods\ >nul
cd build\run
"%JAVA%" -cp ..\miracle-loader.jar;..\fake-minecraft.jar io.github.hronosin.miracle.MiracleMain --username Steve %*
exit /b %errorlevel%
