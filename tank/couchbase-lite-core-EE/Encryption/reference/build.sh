#!/bin/sh
#
# This script demonstrates how to build the SEE command-line tool.
#
CC='gcc -Os -g -Wall -DSQLITE_THREADSAFE=0 -I.'
CC="$CC -DSQLITE_DEBUG=1"
CC="$CC -DSQLITE_ENABLE_FTS4"
CC="$CC -DSQLITE_ENABLE_RTREE"
CC="$CC -DSQLITE_ENABLE_DBPAGE_VTAB"
CC="$CC -DSQLITE_ENABLE_DBSTAT_VTAB"
for module in see-rc4 see-aes128-ofb see-aes128-ccm see-aes256-ofb see-xor see
do
  CMD="cat see-prefix.txt sqlite3.c $module.c"
  echo $CMD ">sqlite-$module.c"
  $CMD >sqlite-$module.c
  CMD="$CC -o $module sqlite-$module.c shell.c -ldl"
  echo $CMD
  $CMD
  ls -l $module
done
if test -r /usr/include/openssl/evp.h; then
  module=see-aes256-openssl
  CMD="cat see-prefix.txt sqlite3.c $module.c"
  echo $CMD ">sqlite-$module.c"
  $CMD >sqlite-$module.c
  CMD="$CC -o $module sqlite-$module.c shell.c -ldl -lssl -lcrypto"
  echo $CMD
  $CMD
  ls -l $module
fi
if test x`uname` = 'xDarwin'; then
  CMD="cat see-prefix.txt sqlite3.c see-cccrypt.c"
  echo $CMD ">sqlite-see-cccrypt.c"
  $CMD >sqlite-see-cccrypt.c
  CMD="$CC -o see-cccrypt -DCCCRYPT256 sqlite-see-cccrypt.c shell.c -ldl"
  echo $CMD
  $CMD
  ls -l $module
fi
cd test-dbs
sh test.sh
