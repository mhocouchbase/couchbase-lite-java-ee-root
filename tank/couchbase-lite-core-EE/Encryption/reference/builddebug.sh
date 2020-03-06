#!/bin/sh
#
# Build the "see" command-line shell for debugging purposes.
#
CC='gcc -O0 -g -Wall -DSQLITE_HAS_CODEC=1 -DSQLITE_THREADSAFE=0 -I.'
CC="$CC -DSQLITE_DEBUG=1"
CC="$CC -DSQLITE_ENABLE_FTS4"
CC="$CC -DSQLITE_ENABLE_RTREE"
CC="$CC -DSQLITE_ENABLE_DBPAGE_VTAB"
CC="$CC -DSQLITE_ENABLE_DBSTAT_VTAB"
for module in see
do
  CMD="cat sqlite3.c $module.c"
  echo $CMD ">sqlite-$module.c"
  $CMD >sqlite-$module.c
  CMD="$CC -o $module sqlite-$module.c shell.c -ldl"
  echo $CMD
  $CMD
  ls -l $module
done
