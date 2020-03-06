
setlocal

rmdir /S /Q bld
mkdir bld

copy sqlite3.c bld
copy sqlite3.h bld
copy sqlite3.def bld
copy shell.c bld
copy see*.c bld

pushd bld

set CC=cl.exe -MT -Zi -Os -W4
set CC=%CC% -D_CRT_SECURE_NO_DEPRECATE -D_CRT_SECURE_NO_WARNINGS
set CC=%CC% -DSQLITE_THREADSAFE=1 -DSQLITE_HAS_CODEC=1
set CC=%CC% -DSQLITE_ENABLE_FTS4=1 -DSQLITE_ENABLE_RTREE=1
set CC=%CC% -DSQLITE_ENABLE_COLUMN_METADATA=1
set CC=%CC% -I.
set LD=link.exe
set MODULES=see-xor see-rc4 see-aes128-ofb see-aes128-ccm see-aes256-ofb see

for %%M in (%MODULES%) do (
  copy /B sqlite3.c + /B %%M.c sqlite-%%M.c /B
  %CC% -Fosqlite-%%M.obj -c sqlite-%%M.c
  %LD% /DLL /DEF:sqlite3.def /OUT:%%M.dll sqlite-%%M.obj
)

popd

endlocal
