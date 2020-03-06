#!/bin/bash

VERSION="2.0.0"

#AAR
declare -a aar=("couchbase-lite-android")
for name in "${aar[@]}"
do
	jar -cvf bundle.jar $name-$VERSION.aar $name-$VERSION.aar.asc \
						$name-$VERSION.pom $name-$VERSION.pom.asc \
						$name-$VERSION-javadoc.jar $name-$VERSION-javadoc.jar.asc \
						$name-$VERSION-sources.jar $name-$VERSION-sources.jar.asc
done
