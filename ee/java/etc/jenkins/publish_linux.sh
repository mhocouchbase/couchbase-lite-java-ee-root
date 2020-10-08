#!/bin/bash
#
# Publish Couchbase Lite Java, Enterprise Edition
#
PRODUCT='couchbase-lite-java-ee'
MAVEN_URL="http://mobile.maven.couchbase.com/maven2/internalmaven"

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

DIST_NAME="${PRODUCT}-${VERSION}-${BUILD_NUMBER}"

echo "======== PUBLISH Couchbase Lite Java, Enterprise Edition v`cat ../../version.txt`-${BUILD_NUMBER}" 
./gradlew ciPublish -PbuildNumber=${BUILD_NUMBER} -PmavenUrl=${MAVEN_URL} || exit 1

echo "======== Copy artifacts to staging directory"
cp "lib/build/distributions/${DIST_NAME}.zip" "${ARTIFACTS}/"
cp lib/build/libs/*.jar "${ARTIFACTS}/"
cp lib/build/publications/couchbaseLiteJava/pom-default.xml "${ARTIFACTS}/pom-ee.xml"

echo "======== Add license to zip"
cd "${WORKSPACE}"
LICENSE_DIR="${DIST_NAME}/license"
rm -rf "${LICENSE_DIR}" || true
mkdir -p "${LICENSE_DIR}"
cp "cbl-java/legal/mobile/couchbase-lite/license/LICENSE_enterprise.txt" "${LICENSE_DIR}/LICENSE.txt" || exit 1
zip -u "${ARTIFACTS}/${DIST_NAME}.zip" "${LICENSE_DIR}/LICENSE.txt"

find "${ARTIFACTS}"
echo "======== PUBLICATION COMPLETE"

