
echo on

rem Build Couchbase Lite Java for Windows, Enterprise Edition

if "%2%" == "" (
    echo Usage: test_windows.bat ^<BUILD_NUMBER^> ^<REPORTS^>
    exit /B 1
)

set buildNumber=%1%
set reportsDir=%2%
set status=0

echo ======== TEST Couchbase Lite Java for Windows, Enterprise Edition 
call gradlew.bat ciTest --console=plain -PautomatedTests=true -PbuildNumber=%buildNumber%  > test.log 2>&1 || set status=5
7z a -tzip -r "%reportsDir%\test-log-windows-ee.zip" test.log

echo ======== Publish test reports
pushd test\build
rmdir /s /q test-results\test\binary
xcopy /e /i /y test-results\test reports\tests\test\raw
cd reports\tests
7z a -tzip -r "%reportsDir%\test-reports-windows-ee.zip" test
popd

echo ======== TEST COMPLETE %status%
exit /B %status%

