#!/bin/bash

VERSION="1.4.1"

#AAR
declare -a aar=("couchbase-lite-android" "couchbase-lite-android-sqlite-custom" "couchbase-lite-android-sqlcipher" "couchbase-lite-android-forestdb")
for name in "${aar[@]}"
do
	cd $name
	pwd
	jar -cvf bundle.jar $name-$VERSION.aar $name-$VERSION.aar.asc \
						$name-$VERSION.pom $name-$VERSION.pom.asc \
						$name-$VERSION-javadoc.jar $name-$VERSION-javadoc.jar.asc \
						$name-$VERSION-sources.jar $name-$VERSION-sources.jar.asc
	cd ..
done

#JAR
declare -a aar=("couchbase-lite-java" "couchbase-lite-java-core" "couchbase-lite-java-javascript" "couchbase-lite-java-listener" "couchbase-lite-java-sqlite-custom" "couchbase-lite-java-sqlcipher" "couchbase-lite-java-forestdb")
for name in "${aar[@]}"
do
	cd $name
	pwd
	jar -cvf bundle.jar $name-$VERSION.jar $name-$VERSION.jar.asc \
						$name-$VERSION.pom $name-$VERSION.pom.asc \
						$name-$VERSION-javadoc.jar $name-$VERSION-javadoc.jar.asc \
						$name-$VERSION-sources.jar $name-$VERSION-sources.jar.asc
	cd ..
done

