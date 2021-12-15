#!/bin/bash

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
ROOT_DIR="${SCRIPT_DIR}/.."

function usage() {
    echo "Usage: $0 <core commit> <ee commit>"
    exit 1
}

function fail() {
    popd > /dev/null
    exit 67
}

if [ "$#" -lt 1 -o "$#" -gt 2 ]; then
    usage
fi

CORE_COMMIT="$1"
if [ -z "$CORE_COMMIT" ]; then
    usage
fi

CORE_EE_COMMIT="$2"

pushd "${ROOT_DIR}" > /dev/null

cd core
if [[ $(git status -s --ignore-submodules=dirty) != '' ]]; then
    echo "Error: Core is dirty"
    fail
fi

if [[ ! -z "$CORE_EE_COMMIT" ]]; then
    if [[ $(git status -s --ignore-submodules=dirty) != '' ]]; then
        echo "Error: Core-EE is dirty"
        fail
    fi
fi

cd ..

echo "=== Update core"
cd core
head=`git rev-parse HEAD`
git checkout prev 2> /dev/null || git checkout -b prev
git reset --hard $head
git remote prune origin
git fetch
git checkout "$CORE_COMMIT" || fail

if [[ ! -z "$CORE_EE_COMMIT" ]]; then
    echo "=== Update core-EE"
    cd  ../couchbase-lite-core-EE
    head=`git rev-parse HEAD`
    git checkout prev 2> /dev/null || git checkout -b prev
    git reset --hard $head
    git remote prune origin
    git fetch
    git checkout "$CORE_EE_COMMIT" || fail
fi

cd ..
${SCRIPT_DIR}/verify_core.sh
if [[ ! $? -eq 0 ]]; then
    cd core
    git checkout -b prev

    if [[ ! -z "$CORE_EE_COMMIT" ]]; then
        cd  ../couchbase-lite-core-EE
        git checkout -b prev
    fi

    echo "Error: incomplete core build"
    fail
fi

echo "=== Fetch new core"
cd core
git checkout working 2> /dev/null || git checkout -b working
git reset --hard "$CORE_COMMIT"
git submodule update --recursive

if [[ ! -z "$CORE_EE_COMMIT" ]]; then
    cd  ../couchbase-lite-core-EE
    git checkout working 2> /dev/null || git checkout -b working
    echo "git reset --hard $CORE_EE_COMMIT"
    git reset --hard "$CORE_EE_COMMIT"
    git submodule update --recursive
fi

echo "=== Fetch"
cd ..
rm -rf common/lite-core
# we used to build it...
# common/tools/build_litecore.sh -e EE
common/tools/fetch_litecore.sh -e EE -n "http://nexus.build.couchbase.com:8081/nexus/content/repositories/releases/com/couchbase/litecore"
common/tools/build_litecore.sh -l mbedcrypto -e EE

popd > /dev/null

