#!/bin/bash

VERSION="2.0.0"

declare -a files=("couchbase-lite-android")
for name in "${files[@]}"
do
	gpg -ab $name-$VERSION.aar
	gpg -ab $name-$VERSION.pom
	gpg -ab $name-$VERSION-javadoc.jar
	gpg -ab $name-$VERSION-sources.jar
done