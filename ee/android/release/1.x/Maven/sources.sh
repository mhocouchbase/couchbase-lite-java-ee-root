#!/bin/bash

VERSION="1.4.1"

#AAR
declare -a aar=("couchbase-lite-android-sqlite-custom" "couchbase-lite-android-sqlcipher" "couchbase-lite-android-forestdb" "couchbase-lite-java-sqlite-custom" "couchbase-lite-java-sqlcipher" "couchbase-lite-java-forestdb" "couchbase-lite-java-javascript")
for name in "${aar[@]}"
do
	cd $name
	pwd
	jar -cvf $name-$VERSION-sources.jar README
	cd ..
done
