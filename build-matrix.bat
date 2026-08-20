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

set "ONLY=%~1"

call :build 1.21.1 21.1.1 1.21.1 2024.11.17 "[1.21.1]"
call :build 1.21.5 21.5.98 1.21.5 2025.06.15 "[1.21.5]"
call :build 1.21.8 21.8.54 1.21.8 2025.09.14 "[1.21.8]"
call :build 1.21.11 21.11.45 1.21.11 2025.12.20 "[1.21.11, 1.22)"
goto :eof

:build
  rem 指定版本时只构建匹配项
  if not "%ONLY%"=="" if not "%ONLY%"=="%~1" goto :eof

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
