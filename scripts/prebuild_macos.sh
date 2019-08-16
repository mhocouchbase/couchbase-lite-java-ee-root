#!/bin/bash -e

pushd ../couchbase-lite-core/build_cmake
./scripts/build_macos_ee.sh

pushd macos
make mbedcrypto
popd

popd
