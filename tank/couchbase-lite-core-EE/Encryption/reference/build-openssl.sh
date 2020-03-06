#!/bin/sh
#
# This script demonstrates how to build the SEE command-line tool.
#
CC='gcc -O0 -g -Wall -DSQLITE_THREADSAFE=0 -I.'
CC="$CC -DSQLITE_DEBUG=1"
CC="$CC -DSQLITE_ENABLE_FTS4"
CC="$CC -DSQLITE_ENABLE_RTREE"
CC="$CC -DSQLITE_ENABLE_DBPAGE_VTAB"
CC="$CC -DSQLITE_ENABLE_DBSTAT_VTAB"
CMD="cat see-prefix.txt sqlite3.c see-aes256-openssl.c"
echo $CMD ">sqlite-see-aes256-openssl.c"
$CMD >sqlite-see-aes256-openssl.c
CMD="$CC -o see-aes256-openssl sqlite-see-aes256-openssl.c shell.c -ldl -lssl -lcrypto"
echo $CMD
$CMD
ls -l see-aes256-openssl
