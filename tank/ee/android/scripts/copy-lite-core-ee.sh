CURRENT_DIR=`pwd`
PROJECT_DIR="${CURRENT_DIR}/../.."
SRC_DIR="${PROJECT_DIR}/libs/couchbase-lite-core-EE"
DES_DIR="${PROJECT_DIR}/libs/couchbase-lite-android/libs"

if [ ! -d "${DES_DIR}/couchbase-lite-core-EE" ]; then
    echo "Copy ${SRC_DIR} to ${DES_DIR}"
    cp -r "${SRC_DIR}" "${DES_DIR}"
fi
