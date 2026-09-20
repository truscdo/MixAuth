@echo off

setlocal enabledelayedexpansion
cd /d "%~dp0"
chcp 65001 >nul

call :load-env

set "ONLY=%~1"

for /f "usebackq eol=# tokens=1-5 delims=|" %%a in ("%~dp0version-matrix.txt") do (
  call :build %%a %%b %%c %%d "%%e"
)
goto :eof

:build
  if not "%ONLY%"=="" if not "%ONLY%"=="%~1" goto :eof

  if "%~1"=="26.1.2" (
    call :resolve-jdk25
    set "JAVA_HOME=%JDK25%"
  ) else if "%~1"=="26.2" (
    call :resolve-jdk25
    set "JAVA_HOME=%JDK25%"
  ) else if "%~1"=="26.3" (
    call :resolve-jdk25
    set "JAVA_HOME=%JDK25%"
  ) else (
    set "JAVA_HOME="
  )

  echo === 构建 MixAuth for MC %~1 / NeoForge %~2 ===

  if exist build\libs rmdir /s /q build\libs
  if exist build\classes rmdir /s /q build\classes

  call gradlew.bat assemble --no-configuration-cache ^
    -Pminecraft_version=%~1 ^
    -Pneo_version=%~2 ^
    -Pparchment_minecraft_version=%~3 ^
    -Pparchment_mappings_version=%~4 ^
    -Pminecraft_version_range="%~5"

  if errorlevel 1 (
    echo 构建 %~1 失败（退出码 !errorlevel!），中止
    exit /b 1
  )

  if exist dist\%~1 rmdir /s /q dist\%~1
  mkdir dist\%~1 2>nul

  set "FOUND=0"
  for %%J in (build\libs\mixauth-%~1-*.jar) do (
    if exist "%%J" (
      copy /y "%%J" "dist\%~1\" >nul
      echo   ^-^> %%~nxJ
      set "FOUND=1"
    )
  )
  if "!FOUND!"=="0" (
    echo 构建 %~1 成功但未找到产物 build\libs\mixauth-%~1-*.jar
    exit /b 1
  )

  echo === 完成 %~1，产物已归档到 dist\%~1\ ===
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
