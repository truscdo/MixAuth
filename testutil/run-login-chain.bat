@echo off
rem ============================================================
rem MixAuth login-chain test - thin forwarder to RunLoginChain.java
rem (stage B cross-platform orchestrator; preserves .bat CLI/env semantics)
rem
rem Usage: run-login-chain.bat <mc-version> [--mock] [--trusted]
rem                            [--user NAME] [--no-build] [--scenario ID]
rem
rem Env overrides: MCC_EXE, MCC_DIR, JDK_EXE, JAVA_HOME, RCON_PW, RCON_PORT,
rem SPORT, MOCK_PORT (same fallback chain as the original .bat).
rem ============================================================
setlocal
cd /d "%~dp0\.."
rem Java resolution (stage-A A1 parity)
set "JDK="
if defined JDK_EXE set "JDK=%JDK_EXE%"
if not defined JDK (
  if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" set "JDK=%JAVA_HOME%\bin\java.exe"
  )
)
rem Fall back to java on PATH (parity with run-login-chain.sh default)
if not defined JDK (
  where java >nul 2>&1 && set "JDK=java"
)
if not defined JDK (
  echo ERROR: java not found - set JDK_EXE or JAVA_HOME, or put java on PATH
  exit /b 1
)
rem Only check file existence for explicit paths; "java" is a PATH command
if not "%JDK%"=="java" (
  if not exist "%JDK%" (
    echo ERROR: java not found: %JDK% - set JDK_EXE or JAVA_HOME
    exit /b 1
  )
)
"%JDK%" --source 21 "%~dp0RunLoginChain.java" %*
exit /b %ERRORLEVEL%