@echo off
rem ============================================================
rem MixAuth 登录链集成测试聚合脚本
rem   默认同时运行离线注册/登录链与在线预检两层（全 4 个版本）
rem
rem 用法：
rem   run-login-it.bat                 两层全跑，全 4 个版本
rem   run-login-it.bat --offline       仅离线注册/登录链
rem   run-login-it.bat --online        仅在线预检（需要本地 mock sessionserver）
rem   run-login-it.bat 1.21.5          仅指定版本
rem   run-login-it.bat 1.21.5 --offline --tests "*S4*"   额外参数透传给 gradlew integrationTest
rem
rem 环境变量：
rem   MCC_EXE / MCC_DIR   MCC 无头客户端二进制（缺失时在线预检层整体跳过，不报红）
rem   RCON_PW / RCON_PORT / SPORT / MOCK_PORT / JDK_EXE / JAVA_HOME
rem
rem 说明：
rem   - 本任务未接入 `check`，作为发布/CI 的独立守门。
rem   - 切换版本前先清理 build/classes、build/libs、build/resources：
rem     Gradle 配置缓存按属性「名称」而非「值」判断 up-to-date，版本切换可能误判。
rem     随后把 L3_CLEAN_BUILD 置为 false，避免测试内部对同一版本重复编译。
rem   - 报告：build/reports/integrationTest、build/test-results/integrationTest；
rem     失败归档：build/reports/integrationTest/artifacts/<场景>/。
rem ============================================================
setlocal enabledelayedexpansion
cd /d "%~dp0"
chcp 65001 >nul

set "ONLY=%~1"
rem 解析层级参数：默认两层全跑；--offline / --online 只跑其中一层；
rem 其余参数（版本过滤之外的）原样透传给 gradlew。
set "LAYER=all"
set "EXTRA_ARGS="
set "IDX=0"
for %%A in (%*) do (
  set /a IDX+=1
  if !IDX! GTR 1 (
    if /i "%%A"=="--offline" ( set "LAYER=offline" ) else if /i "%%A"=="--online" ( set "LAYER=online" ) else ( set "EXTRA_ARGS=!EXTRA_ARGS! %%A" )
  )
)
rem 按层级设置 JUnit tag 过滤参数；用户额外传入的 --tests 仍会透传。
set "TEST_FILTERS="
if "%LAYER%"=="offline" (
  set "TEST_FILTERS=-Plct.layer=offline"
) else if "%LAYER%"=="online" (
  set "TEST_FILTERS=-Plct.layer=online"
) else if "%EXTRA_ARGS%"=="" (
  set "TEST_FILTERS=-Plct.layer=all"
)

call :it 1.21.1 21.1.1 1.21.1 2024.11.17 "[1.21.1]"
call :it 1.21.5 21.5.98 1.21.5 2025.06.15 "[1.21.5]"
call :it 1.21.8 21.8.54 1.21.8 2025.09.14 "[1.21.8]"
call :it 1.21.11 21.11.45 1.21.11 2025.12.20 "[1.21.11]"
goto :eof

:it
  if not "%ONLY%"=="" if not "%ONLY%"=="%~1" goto :eof

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