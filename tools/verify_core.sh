#!/bin/bash

NEXUS_URL="http://nexus.build.couchbase.com:8081/nexus/content/repositories/releases/com/couchbase/litecore"
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
TOOLS_DIR="${SCRIPT_DIR}/../common/tools"
STATUS=0

test_core() {
   OS=$1
   EDITION=$2
   SHA=$3
   SUFFIX=$4
   echo -n "${OS} ${EDITION}: "
   curl -I -s -f -o /dev/null "${NEXUS_URL}/couchbase-litecore-${OS}/${SHA}/couchbase-litecore-${OS}-${SHA}.${SUFFIX}" -o "${OS}-${EDITION}.${SUFFIX}"
   if [ $? -eq 0 ]; then
      echo "Succeeded"
   else
      echo "Failed"
      STATUS=67
   fi
}

rm -rf .core-tmp
mkdir .core-tmp
pushd .core-tmp > /dev/null

echo -n "CE SHA: "
"${TOOLS_DIR}/litecore_sha.sh" -e CE -o .core-sha
CE_SHA=`cat .core-sha`

echo -n "EE SHA: "
"${TOOLS_DIR}/litecore_sha.sh" -e EE -o .core-sha
EE_SHA=`cat .core-sha`

test_core "centos6" "CE" "${CE_SHA}" "tar.gz"
test_core "centos6" "EE" "${EE_SHA}" "tar.gz"

for OS in macosx windows-win64; do
   test_core "${OS}" "CE" "${CE_SHA}" "zip"
   test_core "${OS}" "EE" "${EE_SHA}" "zip"
done

popd > /dev/null
rm -rf .core-tmp

exit $STATUS

