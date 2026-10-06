@echo off
rem The application, http://localhost:8095, with every plugin of this build and — when it is built
rem beside this repository — the plugins of recommend4me-f95zone.
rem Build first, once and after every change: gradlew.bat installDist
rem
rem A COPY IS RUN, not the build's own folder: the JVM reads classes from the jars as it goes, and a
rem rebuild under a running process leaves it without the classes it had not loaded yet.
rem
rem "run.bat gpu" runs the encoders on the NVIDIA card, with the CUDA libraries of tools\fetch-cuda.ps1.
cd /d "%~dp0"
title recommend4me
if not exist app\build\install\recommend4me\bin\recommend4me.bat (
  echo The application is not built: run gradlew.bat installDist first
  exit /b 1
)
robocopy app\build\install\recommend4me build\run /MIR /NFL /NDL /NJH /NJS /NP >nul
set PLUGINS=%~dp0build\run\plugins
if exist ..\recommend4me-f95zone\plugin\build\plugin (
  robocopy ..\recommend4me-f95zone\plugin\build\plugin build\run-extra /MIR /NFL /NDL /NJH /NJS /NP >nul
  set PLUGINS=%~dp0build\run\plugins;%~dp0build\run-extra
)
set GPU=false
if /i "%~1"=="gpu" (
  set GPU=true
  rem The card may be newer than the kernels of ONNX Runtime: the driver compiles them on the first
  rem launch and keeps them in its cache, made large enough here to hold them all
  set CUDA_CACHE_MAXSIZE=4294967296
)
set DATA=%LOCALAPPDATA%\recommend4me
if not exist "%DATA%" mkdir "%DATA%"
rem A death leaves something behind: the crash file and the heap at the moment it ran out
set JAVA_OPTS=-Drecommend4me.plugin-dirs="%PLUGINS%" -Drecommend4me.gpu=%GPU% -XX:ErrorFile="%DATA%\hs_err_pid%%p.log" -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath="%DATA%"
call build\run\bin\recommend4me.bat %*
