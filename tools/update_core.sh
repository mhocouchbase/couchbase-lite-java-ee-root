#!/bin/bash

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
ROOT_DIR="${SCRIPT_DIR}/.."

echo "=== Update core"
pushd "${ROOT_DIR}/core" > /dev/null
CUR_BRANCH="$( git symbolic-ref HEAD )"
if [ "${CUR_BRANCH}" != "refs/heads/master" ]; then
    echo "not on master branch in `pwd`"
    exit 1
fi
git pull
git submodule update --recursive
git remote prune origin
popd > /dev/null

echo "=== Update EE core"
pushd  "${ROOT_DIR}/couchbase-lite-core-EE" > /dev/null
CUR_BRANCH="$( git symbolic-ref HEAD )"
if [ "${CUR_BRANCH}" != "refs/heads/master" ]; then
    echo "not on master branch in `pwd`"
    exit 1
fi
git pull
git submodule update --recursive
git remote prune origin
popd > /dev/null

echo "=== Rebuild core lib"
pushd "${ROOT_DIR}" > /dev/null
rm -rf common/lite-core
common/tools/build_litecore.sh -e EE
popd > /dev/null

