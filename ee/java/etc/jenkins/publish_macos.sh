#!/bin/bash -e
#
# Publish Couchbase Lite Java, Enterprise Edition
#

PRODUCT='couchbase-lite-java-ee'

function usage() {
    echo "Usage: $0 <release version> <build number> <artifacts path> <workspace path>"
    exit 1
}

if [ "$#" -ne 4 ]; then
    usage
fi

VERSION="$1"
if [ -z "$VERSION" ]; then
    usage
fi

BUILD_NUMBER="$2"
if [ -z "$BUILD_NUMBER" ]; then
    usage
fi

ARTIFACTS="$3"
if [ -z "$ARTIFACTS" ]; then
    usage
fi

WORKSPACE="$4"
if [ -z "$WORKSPACE" ]; then
    usage
fi

echo "======== PUBLISH Couchbase Lite Java, Enterprise Edition v`cat ../../version.txt`-${BUILD_NUMBER}" 
cp "lib/build/distributions/${PRODUCT}-${VERSION}-${BUILD_NUMBER}.zip" "${ARTIFACTS}/${PRODUCT}-${VERSION}-${BUILD_NUMBER}-macos.zip"

find "${ARTIFACTS}"
echo "======== PUBLICATION COMPLETE"
