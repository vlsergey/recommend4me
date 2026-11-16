@echo off
rem The application, http://localhost:8095, with every plugin of this build and — when it is built
rem beside this repository — the plugins of recommend4me-f95zone.
rem Build first, once and after every change: gradlew.bat installDist
rem
rem A COPY IS RUN, not the build's own folder: the JVM reads classes from the jars as it goes, and a
rem rebuild under a running process leaves it without the classes it had not loaded yet.
rem
rem "run.bat gpu" runs the encoders on the NVIDIA card, with the CUDA libraries of tools\fetch-cuda.ps1.
rem The data are kept in data\ beside this file.
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
rem The data folder: data\ beside this file, ignored by git — not %LOCALAPPDATA%, the application's own default
rem (16.11.2026: the system disk is not where tens of gigabytes of the databases, pictures and models go)
set DATA=%~dp0data
if not exist "%DATA%" mkdir "%DATA%"
rem A death leaves something behind: the crash file and the heap at the moment it ran out.
rem ZGC, not G1: G1 crashed four times (30.10–4.11.2026) with an access violation in a GC thread
rem scanning a request's virtual thread in Recommendations.explain, compiled on the stack
rem The log of the collector and of the stops at safepoints, gc.log beside the application's own, ten files of
rem 50 MB in turn: whether a stall of every request at once (16.11.2026, a minute and a half) is the collector's —
rem an allocation stall, a long stop — or not
set GCLOG=-Xlog:gc*,safepoint:file="%DATA%\gc.log":time,uptime,level,tags:filecount=10,filesize=50m
set JAVA_OPTS=-Drecommend4me.data-dir="%DATA%" -Drecommend4me.plugin-dirs="%PLUGINS%" -Drecommend4me.gpu=%GPU% -XX:+UseZGC %GCLOG% -XX:ErrorFile="%DATA%\hs_err_pid%%p.log" -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath="%DATA%"
call build\run\bin\recommend4me.bat %*
