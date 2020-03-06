echo ""
echo "Java: "
cloc . --include-ext=java --quiet

echo ""
echo "JNI: "
pushd libs/couchbase-lite-android/libs/couchbase-lite-core/Java > /dev/null
cloc . --include-ext=h,cc --quiet
popd
