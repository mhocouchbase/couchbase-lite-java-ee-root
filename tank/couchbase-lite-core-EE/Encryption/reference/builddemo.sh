#!/bin/sh
#
# This script demonstrates how to build a "demonstration" version of the
# SEE command-line shell.  The demonstration version uses a fixed key
# of 'demo'.
#
CC='gcc -Os -DSQLITE_HAS_CODEC=1 -DSQLITE_THREADSAFE=0 -I. -DDEMO_ONLY=1'
#CC="$CC -DSQLITE_DEBUG=1"
CC="$CC -DSQLITE_ENABLE_FTS4"
CC="$CC -DSQLITE_ENABLE_RTREE"
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
