#!/bin/bash

CP_DIR=`pwd`
pushd /Users/blakemeike/Working/java/lithium/ee/java >/dev/null

pwd
gw distZip

cp lib/build/distributions/couchbase-lite-java-*.zip "${CP_DIR}"
cp -a lib/build/classes "${CP_DIR}"
cp -a lib/build/resources "${CP_DIR}"

popd

unzip couchbase-lite-java-*.zip
mv couchbase-lite-java-*/lib/* .
mv couchbase-lite-java-*.jar cbl-java.jar
rm -rf couchbase-lite-java-*

cp `find ~/.gradle | egrep 'files.+/hamcrest-core-1.3.jar'` .
cp `find ~/.gradle | egrep 'files.+/junit-4.13.1.jar'` .
cp `find ~/.gradle | egrep 'files.+/kotlin-stdlib.+1.4.21.jar'` .

