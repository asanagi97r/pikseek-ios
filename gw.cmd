@echo off
rem PikSeek Gradle entry. Every tool and cache lives inside the project under _runtime\pikseek;
rem nothing is written to the system or the user profile. Variables are set for this process only.
rem   gw.cmd :desktopApp:compileKotlinDesktop
rem   gw.cmd :desktopApp:createReleaseDistributable
rem (ASCII only: cmd reads batch files in the OEM code page.)
setlocal
set "SRC=%~dp0"
for %%I in ("%SRC%..\..") do set "ROOT=%%~fI"
set "RT=%ROOT%\_runtime\pikseek"
if not exist "%RT%\jdk\bin\java.exe" (
    echo JDK not found at %RT%\jdk - see 30_code\pikseek\BUILD.md
    exit /b 2
)
for %%D in (g h t b h\AppData\Local h\AppData\Roaming) do if not exist "%RT%\%%D" mkdir "%RT%\%%D"
set "JAVA_HOME=%RT%\jdk"
set "GRADLE_USER_HOME=%RT%\g"
set "TEMP=%RT%\t"
set "TMP=%RT%\t"
rem Kotlin daemon, skiko, JNA and friends write under the user profile; keep those here too
set "LOCALAPPDATA=%RT%\h\AppData\Local"
set "APPDATA=%RT%\h\AppData\Roaming"
set "RTF=%RT:\=/%"
set "JAVA_TOOL_OPTIONS=-Duser.home=%RTF%/h"
call "%SRC%gradlew.bat" --project-cache-dir "%RTF%/b/.gradle" "-Ppikseek.buildRoot=%RTF%/b" "-Pkotlin.project.persistent.dir=%RTF%/b/.kotlin" "-Porg.gradle.java.installations.paths=%RTF%/jdk" %*
exit /b %ERRORLEVEL%
