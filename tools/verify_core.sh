#!/bin/sh

NEXUS_URL="http://nexus.build.couchbase.com:8081/nexus/content/repositories/releases/com/couchbase/litecore"
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
TOOLS_DIR="${SCRIPT_DIR}/../common/tools"

mkdir .core-tmp
pushd .core-tmp

HERE=`pwd`

"${TOOLS_DIR}/fetch_litecore.sh" -e EE -n "${NEXUS_URL}" -o "${HERE}" 

popd
rm -rf .core-tmp

