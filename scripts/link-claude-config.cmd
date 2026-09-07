@echo off
REM Recreate the .claude/ junctions after a fresh clone.
REM
REM .agents/ holds the tracked source for Claude Code configuration; .claude/ is gitignored and
REM contains only directory junctions into it, so the config is shared through git while the
REM tool-specific directory stays out of the repository.
REM
REM Junctions (mklink /J) are used rather than symlinks because they need no elevation on Windows.
REM Run from anywhere; paths are resolved relative to this script.

setlocal
set "ROOT=%~dp0.."
pushd "%ROOT%" || exit /b 1

if not exist ".claude" mkdir ".claude"

call :link skills
call :link agents

echo.
echo Done. Restart Claude Code so it re-reads the configuration.
popd
endlocal
exit /b 0

:link
if exist ".claude\%~1" (
    echo   .claude\%~1 already exists - leaving it alone
    exit /b 0
)
if not exist ".agents\%~1" (
    echo   .agents\%~1 does not exist - skipping
    exit /b 0
)
mklink /J ".claude\%~1" ".agents\%~1" >nul
if errorlevel 1 (
    echo   FAILED to link .claude\%~1
    exit /b 1
)
echo   linked .claude\%~1 -^> .agents\%~1
exit /b 0
