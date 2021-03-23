#!/bin/bash

cd core/vendor/zlib
git clean -xdf
git reset --hard HEAD

cd ../../../common
git clean -xdf -e'lite-core'

cd ../ce
git clean -xdf -e'.idea' -e'local*'

cd ..
git clean -xdf -e'.idea' -e'local*'

