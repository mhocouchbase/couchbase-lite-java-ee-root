#!/bin/bash
#
# Build Couchbase Lite Java for Linux, MacOS, Windows, Enterprise Edition
# This script assumes the the OSX and Windows builds are available on latestbuilds
#
PRODUCT="couchbase-lite-java-ee"
LATESTBUILDS_URL="http://latestbuilds.service.couchbase.com/builds/latestbuilds"
NEXUS_URL="http://nexus.build.couchbase.com:8081/nexus/content/repositories/releases/com/couchbase/litecore"

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
TOOLS_DIR="${SCRIPT_DIR}/../../../../common/tools"

function usage() {
   echo "Usage: $0 <release version> <build number> <workspace path>"
   exit 1
}

if [ "$#" -ne 3 ]; then
   usage
fi

VERSION="$1"
if [ -z "${VERSION}" ]; then
   usage
fi

BUILD_NUMBER="$2"
if [ -z "${BUILD_NUMBER}" ]; then
   usage
fi

WORKSPACE="$3"
if [ -z "${WORKSPACE}" ]; then
   usage
fi

echo "======== BUILD Couchbase Lite Java for Linux, Enterprise Edition v`cat ../../version.txt`-${BUILD_NUMBER}"

echo "======== Clean up ..." 
"${TOOLS_DIR}/clean_litecore.sh"

echo "======== Download platform artifacts ..."
for PLATFORM in macos windows; do
   ARTIFACT="${PRODUCT}-${VERSION}-${BUILD_NUMBER}-${PLATFORM}.zip"
   ARTIFACT_URL="${LATESTBUILDS_URL}/couchbase-lite-java/${VERSION}/${BUILD_NUMBER}"
   "${TOOLS_DIR}/extract_libs.sh" "${ARTIFACT_URL}" "${ARTIFACT}" "${WORKSPACE}/zip-tmp" || exit 1
done

echo "======== Download Lite Core ..."
"${TOOLS_DIR}/fetch_litecore.sh" -p "linux" -e EE -n "${NEXUS_URL}"

echo "======== Build Java"
./gradlew ciBuild -PbuildNumber="${BUILD_NUMBER}" || exit 1

echo "======== BUILD COMPLETE"
find lib/build/distributions
