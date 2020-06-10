
echo on

rem Build Couchbase Lite Java, Enterprise Edition for Windows

if "%2%" == "" (
    echo Usage: test_windows.bat ^<BUILD_NUMBER^> ^<REPORTS^>
    exit /B 1
)

set buildNumber=%1%
set reportsDir=%2%
set status=0

echo ======== TEST Couchbase Lite Java, Enterprise Edition 
call gradlew.bat ciTest --info --console=plain || set status=1

echo ======== Publish test reports
pushd lib\build\reports
7z a -tzip -r "%reportsDir%\test-reports-windows-ee.zip" tests
popd

echo ======== TEST COMPLETE %status%
exit /B %status%

