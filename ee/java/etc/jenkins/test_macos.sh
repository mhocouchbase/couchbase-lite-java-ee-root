#!/bin/bash
#
# Test Couchbase Lite Java, Enterprise Edition for MacOS
#
function usage() {
    echo "Usage: $0 <build number> <reports path>"
    exit 1
}

if [ "$#" -ne 2 ]; then
    usage
fi

BUILD_NUMBER="$1"
if [ -z "$BUILD_NUMBER" ]; then
    usage
fi

REPORTS="$2"
if [ -z "REPORTS" ]; then
    usage
fi

STATUS=0

echo "======== TEST Couchbase Lite Java, Enterprise Edition v`cat ../../version.txt`-${BUILD_NUMBER}"
./gradlew ciTest --info --console=plain || STATUS=1

echo "======== Publish reports"
pushd lib/build/reports
zip -r "${REPORTS}/test-reports-macos-ee" tests
popd

find "${REPORTS}"
echo "======== TEST COMPLETE ${STATUS}"
exit $STATUS

