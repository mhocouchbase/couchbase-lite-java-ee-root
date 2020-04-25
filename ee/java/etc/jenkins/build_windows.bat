@echo ON

:: Build Couchbase Lite Java, Enterprise Edition for Windows

SET liteCoreRepoUrl="http://nexus.build.couchbase.com:8081/nexus/content/repositories/releases/com/couchbase/litecore"

if "%2%" == "" (
    echo Usage: build_windows.bat ^<VS Generator: 2015,2017,2019^> ^<BUILD_NUMBER^>
    exit /B 1
)

set vsGen=%1%
set buildNumber=%2%

pushd %~dp0
set scriptDir=%CD%
popd
set toolsDir=%scriptDir%\..\..\..\..\common\tools


echo ======== BUILD Couchbase Lite Java, Enterprise Edition

echo ======== Download Lite Core
powershell.exe -ExecutionPolicy Bypass -Command "%toolsDir%\fetch_litecore.ps1" %liteCoreRepoUrl% EE || goto error

echo ======== Build mbedcrypto
call %toolsDir%\build_litecore.bat %vsGen% EE mbedcrypto || goto error

echo ======== Build
echo "" > local.properties
call gradlew.bat ciBuild -PbuildNumber=%buildNumber% || goto error

echo ======== BUILD COMPLETE

goto :eof

:error
echo Failed with error %ERRORLEVEL%.
exit /b %ERRORLEVEL%
