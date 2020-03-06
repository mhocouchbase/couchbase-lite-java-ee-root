#!/bin/bash

VERSION="1.4.1"

#AAR
declare -a aar=("couchbase-lite-android" "couchbase-lite-android-sqlite-custom" "couchbase-lite-android-sqlcipher" "couchbase-lite-android-forestdb")
for name in "${aar[@]}"
do
	echo "$name"
	mkdir -p $name
	cd $name
	pwd
	wget http://files.couchbase.com/maven2/com/couchbase/lite/$name/$VERSION/$name-$VERSION-sources.jar
	wget http://files.couchbase.com/maven2/com/couchbase/lite/$name/$VERSION/$name-$VERSION.aar
	wget http://files.couchbase.com/maven2/com/couchbase/lite/$name/$VERSION/$name-$VERSION.pom
	cd ..
done

#JAR
declare -a aar=("couchbase-lite-java" "couchbase-lite-java-core" "couchbase-lite-java-javascript" "couchbase-lite-java-listener" "couchbase-lite-java-sqlite-custom" "couchbase-lite-java-sqlcipher" "couchbase-lite-java-forestdb")
for name in "${aar[@]}"
do
	echo "$name"
	mkdir -p $name
	cd $name
	pwd
	wget http://files.couchbase.com/maven2/com/couchbase/lite/$name/$VERSION/$name-$VERSION-sources.jar
	wget http://files.couchbase.com/maven2/com/couchbase/lite/$name/$VERSION/$name-$VERSION.jar
	wget http://files.couchbase.com/maven2/com/couchbase/lite/$name/$VERSION/$name-$VERSION.pom
	cd ..
done

