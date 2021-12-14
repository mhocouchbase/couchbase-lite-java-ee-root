#!/bin/bash

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
ROOT_DIR="${SCRIPT_DIR}/.."

restore() {
    pushd "${ROOT_DIR}/core" > /dev/null
    git checkout master
    popd > /dev/null
    pushd  "${ROOT_DIR}/couchbase-lite-core-EE" > /dev/null
    git checkout master
    popd > /dev/null
}


echo "=== Verify new core"
pushd "${ROOT_DIR}/core" > /dev/null
CUR_BRANCH="$( git symbolic-ref HEAD )"
if [ "${CUR_BRANCH}" != "refs/heads/master" ]; then
    echo "not on master branch in `pwd`"
    restore
    exit 1
fi
git checkout origin/master
popd > /dev/null

pushd  "${ROOT_DIR}/couchbase-lite-core-EE" > /dev/null
CUR_BRANCH="$( git symbolic-ref HEAD )"
if [ "${CUR_BRANCH}" != "refs/heads/master" ]; then
    echo "not on master branch in `pwd`"
    restore
    exit 1
fi
git checkout origin/master
popd > /dev/null

${SCRIPT_DIR}/verify_core.sh
if [ $? -eq 0 ]; then
    echo "incomplete core build"
    restore
    exit 1
fi

echo "=== Update core"
pushd "${ROOT_DIR}/core" > /dev/null
git checkout master
git reset --hard origin/master
git submodule update --recursive
git remote prune origin
popd > /dev/null

echo "=== Update EE"
pushd  "${ROOT_DIR}/couchbase-lite-core-EE" > /dev/null
git checkout master
git reset --hard origin/master
git submodule update --recursive
git remote prune origin
popd > /dev/null

echo "=== Fetch new core"
pushd "${ROOT_DIR}" > /dev/null
rm -rf common/lite-core
# we used to build it...
# common/tools/build_litecore.sh -e EE
common/tools/fetch_litecore.sh -e EE -n "http://nexus.build.couchbase.com:8081/nexus/content/repositories/releases/com/couchbase/litecore"
common/tools/build_litecore.sh -l mbedcrypto -e EE
popd > /dev/null

