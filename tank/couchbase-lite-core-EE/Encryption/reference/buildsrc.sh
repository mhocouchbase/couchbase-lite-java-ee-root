#!/bin/sh
#
# This script builds the various sqlite3 C-code source files:
#
#  (1)   sqlite3-see.c                   Combination of (2), (3), and (6)
#  (2)   sqlite3-see-aes128-ofb.c        AES-128 OFB
#  (3)   sqlite3-see-aes256-ofb.c        AES-256 OFB
#  (4)   sqlite3-see-aes128-ccm.c        AES-128 CCM
#  (5)   sqlite3-see-cccrypt.c           AES using cccrypt
#  (6)   sqlite3-see-rc4.c               RC4 (legacy - do not use)
#  (7)   sqlite3-see-xor.c               XOR (demonstation only)
#     
#
for module in see see-aes128-ofb see-aes256-ofb see-aes128-ccm see-cccrypt \
     see-rc4 see-xor see-aes256-openssl
do
  CMD="cat see-prefix.txt sqlite3.c $module.c"
  # echo $CMD ">sqlite-$module.c"
  $CMD >sqlite-$module.c
  ls -l sqlite-$module.c
done
