#
# CI Build script for Enterprise Android
#
GROUP='com.couchbase.lite'
PRODUCT='coucbase-lite-android-ee'
EDITION='enterprise'

# These versions must match the versions in lib/build.gradle
NDK_VERSION='20.1.5948944'
CMAKE_VERSION='3.10.2.4988404'
BUILD_TOOLS_VERSION='29.0.3'


function usage() {
    echo "Usage: $0 <sdk path>"
    exit 1
}

if [ "$#" -ne 1 ]; then
    usage
fi

SDK_HOME="$1"
if [ -z "$SDK_HOME" ]; then
    usage
fi

SDK_MGR="${SDK_HOME}/tools/bin/sdkmanager"

echo "======== CHECK Couchbase Lite Java, Enterprise Edition v`cat ../version.txt`"

echo "======== Install Toolchain"
yes | ${SDK_MGR} --licenses > /dev/null 2>&1
${SDK_MGR} --install "build-tools;${BUILD_TOOLS_VERSION}"
${SDK_MGR} --install "cmake;${CMAKE_VERSION}"
${SDK_MGR} --install "ndk;${NDK_VERSION}"

cat <<EOF >> local.properties
sdk.dir=${SDK_HOME}
ndk.dir=${SDK_HOME}/ndk/${NDK_VERSION}
cmake.dir=${SDK_HOME}/cmake/${CMAKE_VERSION}
EOF

echo "======== Build Core"
common/tools/build_litecore.sh -e EE

echo "======== Check"
./gradlew ciBuild || exit 1

echo "======== CHECK COMPLETE"
