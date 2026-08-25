@echo off
rem MixAuth 多版本构建矩阵（每主流版本单独构建）
rem
rem 用法：
rem   build-matrix.bat             构建全部 4 个主流版本
rem   build-matrix.bat 1.21.5      只构建指定版本
rem
rem 说明：
rem   - 通过 -P 覆盖 gradle.properties 中的版本属性（Gradle 优先级：命令行 > gradle.properties）
rem   - 产物归档到 dist/<mc>/，命名 mixauth-<mc>-<mod_version>.jar

setlocal enabledelayedexpansion
cd /d "%~dp0"
chcp 65001 >nul

rem 加载 .env（若存在）：JDK25_HOME / MCC_DIR 等机器特定配置集中于此
call :load-env

set "ONLY=%~1"

call :build 1.21.1 21.1.1 1.21.1 2024.11.17 "[1.21.1]"
call :build 1.21.5 21.5.98 1.21.5 2025.06.15 "[1.21.5]"
call :build 1.21.8 21.8.54 1.21.8 2025.09.14 "[1.21.8]"
call :build 1.21.11 21.11.45 1.21.11 2025.12.20 "[1.21.11, 1.22)"
rem 26.1：最低稳定 NeoForge 26.1.2.71；26.1 起无需 Parchment（官方参数名可用），
rem 故 parchment 参数传占位符 "-"（build.gradle 的 26.1 分支不读取）。
call :build 26.1 26.1.2.71 - - "[26.1, 26.2)"
rem 26.2：最新稳定线 26.2.0.67；同 26.1 无需 Parchment。
call :build 26.2 26.2.0.67 - - "[26.2, 26.3)"
goto :eof

:build
  rem 指定版本时只构建匹配项
  if not "%ONLY%"=="" if not "%ONLY%"=="%~1" goto :eof

  rem 26.1 起 Minecraft 使用 Java 25，Gradle 守护进程须以 JDK 25 运行
  rem （JDK 21 守护进程下载 NeoForge Maven 依赖时 TLS 握手失败）；
  rem 其余版本沿用系统默认 JDK（21）。
  if "%~1"=="26.1" (
    call :resolve-jdk25
    set "JAVA_HOME=%JDK25%"
  ) else if "%~1"=="26.2" (
    call :resolve-jdk25
    set "JAVA_HOME=%JDK25%"
  ) else (
    set "JAVA_HOME="
  )

  echo === 构建 MixAuth for MC %~1 / NeoForge %~2 ===

  rem 删除上一版本遗留的编译产物与 jar，强制 compileJava + jar 重新执行。
  rem 实测：跨版本切换时 Gradle 会把 compileJava/jar 误判为 up-to-date
  rem （配置缓存仅按 -P 属性名键控、不按值），导致沿用旧版本类或不产 jar。
  rem 保留 build/moddev 与 build/tmp，以复用各版本 minecraft artifact 缓存。
  if exist build\libs rmdir /s /q build\libs
  if exist build\classes rmdir /s /q build\classes

  rem 生产构建用 assemble 而非 build：java 插件的 build 生命周期默认包含
  rem check → test → compileTestJava，会把 dev 专属的测试 mod（src/test 下的
  rem @Mod("mixauth_tests") 与 GameTest 测试）编译进每个目标版本，且其引用的
  rem vanilla API（如 1.21.2+ 移除的 @GameTest）会阻塞跨版本构建。
  rem assemble 只产出 jar + jarJar（生产产物），测试仅在 dev 的 gameTestServer 验证。
  rem --no-configuration-cache：避免跨版本复用配置缓存造成 up-to-date 误判
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

  rem 归档产物（整目录重建避免残留旧版本 jar；只拷当前版本命名的产物）
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
  rem 解析 JDK 25 安装路径（不硬编码任何机器特定目录）：
  rem   1) JDK25_HOME 环境变量（推荐，如 set JDK25_HOME=C:\path\to\jdk-25）
  rem   2) JAVA_HOME（若其 release 文件声明 Java 25）
  rem   3) 常见安装位置自动探测（Program Files / LocalAppData 下的 jdk-25*）
  rem   4) 均未找到 → 报错退出
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
  rem 加载项目根目录 .env（若存在）：每行 KEY=VALUE，# 开头为注释。
  rem 已定义的环境变量优先，.env 仅填充缺失项（系统级/命令行设置优先）。
  if not exist ".env" goto :eof
  for /f "usebackq eol=# tokens=1,* delims==" %%A in (".env") do (
    if not defined %%A set "%%A=%%B"
  )
  goto :eof
