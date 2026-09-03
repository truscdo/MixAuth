@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"
chcp 65001 >nul

call :load-env

set "ONLY=%~1"
set "LAYER=all"
set "EXTRA_ARGS="
set "IDX=0"
for %%A in (%*) do (
  set /a IDX+=1
  if !IDX! GTR 1 (
    if /i "%%A"=="--offline" ( set "LAYER=offline" ) else if /i "%%A"=="--online" ( set "LAYER=online" ) else ( set "EXTRA_ARGS=!EXTRA_ARGS! %%A" )
  )
)
set "TEST_FILTERS="
if "%LAYER%"=="offline" (
  set "TEST_FILTERS=-Plct.layer=offline"
) else if "%LAYER%"=="online" (
  set "TEST_FILTERS=-Plct.layer=online"
) else if "%EXTRA_ARGS%"=="" (
  set "TEST_FILTERS=-Plct.layer=all"
)

for /f "usebackq eol=# tokens=1-5 delims=|" %%a in ("%~dp0version-matrix.txt") do (
  call :it %%a %%b %%c %%d "%%e"
)
goto :eof

:it
  if not "%ONLY%"=="" if not "%ONLY%"=="%~1" goto :eof

  if "%~1"=="26.1.2" (
    call :resolve-jdk25
    set "JAVA_HOME=%JDK25%"
  ) else if "%~1"=="26.2" (
    call :resolve-jdk25
    set "JAVA_HOME=%JDK25%"
  ) else (
    set "JAVA_HOME="
  )

  echo === integrationTest (%LAYER%) for MC %~1 / NeoForge %~2 ===

  if exist build\classes rmdir /s /q build\classes
  if exist build\libs rmdir /s /q build\libs
  if exist build\resources rmdir /s /q build\resources
  if exist build\reports\integrationTest rmdir /s /q build\reports\integrationTest

  set "L3_CLEAN_BUILD=false"
  call gradlew.bat integrationTest --no-configuration-cache %TEST_FILTERS% %EXTRA_ARGS% ^
    -Pminecraft_version=%~1 ^
    -Pneo_version=%~2 ^
    -Pparchment_minecraft_version=%~3 ^
    -Pparchment_mappings_version=%~4 ^
    -Pminecraft_version_range="%~5"

  if errorlevel 1 (
    echo ** %~1 integrationTest failed, error code: !errorlevel! 
    exit /b !errorlevel!
  )
  echo === DONE %~1 ===
  goto :eof

:resolve-jdk25
  set "JDK25="
  if defined JDK25_HOME if exist "%JDK25_HOME%\bin\java.exe" set "JDK25=%JDK25_HOME%"
  if not defined JDK25 if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" (
    findstr /c:"JAVA_VERSION=""25" "%JAVA_HOME%\release" >nul 2>nul && set "JDK25=%JAVA_HOME%"
  )
  if not defined JDK25 (
    for %%D in ("%ProgramFiles%\Java\jdk-25*" "%ProgramFiles%\Eclipse Adoptium\jdk-25*" "%ProgramFiles%\Microsoft\jdk-25*" "%LOCALAPPDATA%\Programs\Eclipse Adoptium\jdk-25*" "%LOCALAPPDATA%\Programs\Microsoft\jdk-25*") do (
      if not defined JDK25 if exist "%%~D\bin\java.exe" set "JDK25=%%~D"
    )
  )
  if not defined JDK25 (
    echo [ERROR] 未找到 JDK 25。请设置环境变量 JDK25_HOME 指向 JDK 25 安装目录后重试。
    exit /b 1
  )
  echo   使用 JDK 25: %JDK25%
  goto :eof

:load-env
  if not exist ".env" goto :eof
  for /f "usebackq eol=# tokens=1,* delims==" %%A in (".env") do (
    if not defined %%A set "%%A=%%B"
  )
  goto :eof