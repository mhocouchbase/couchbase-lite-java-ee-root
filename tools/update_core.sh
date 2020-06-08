#!/bin/bash

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
ROOT_DIR="${SCRIPT_DIR}/.."
CUR_DIR=`pwd`

echo "=== Update core"
cd "${ROOT_DIR}/core"
CUR_BRANCH="$( git symbolic-ref HEAD )"
if [ "${CUR_BRANCH}" != "refs/heads/master" ]; then
    echo "not on master branch in `pwd`"
    exit 1
fi
git pull
git submodule update --recursive
git remote prune origin

echo "=== Update EE core"
cd "${ROOT_DIR}/couchbase-lite-core-EE"
CUR_BRANCH="$( git symbolic-ref HEAD )"
if [ "${CUR_BRANCH}" != "refs/heads/master" ]; then
    echo "not on master branch in `pwd`"
    exit 1
fi
git pull
git submodule update --recursive
git remote prune origin

echo "=== Rebuild core lib"
cd "${ROOT_DIR}"
rm -rf common/lite-core
common/tools/build_litecore.sh -e EE

cd "${CUR_DIR}"

