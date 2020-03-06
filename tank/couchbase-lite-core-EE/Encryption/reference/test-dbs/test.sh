#!/bin/sh
#
# With the various SEE shells compiled in the parent directory, run this
# script to ensure that all the test databases are readable.
#
echo '*********** ../see **************'
../see -key xyzzy demo01.aes128ofb .selftest
../see -key aes128:xyzzy demo01.aes128ofb .selftest
../see -key aes256:xyzzy demo01.aes256ofb .selftest
../see -key rc4:xyzzy demo01.rc4 .selftest
../see -textkey xyzzy textkey.aes128ofb .selftest
../see -hexkey 78797a7a79 demo01.aes128ofb .selftest
../see demo01.aes128ofb --cmd "PRAGMA key='xyzzy';" .selftest
../see demo01.aes128ofb --cmd "PRAGMA hexkey='78797a7a79';" .selftest
../see textkey.aes128ofb --cmd "PRAGMA textkey='xyzzy';" .selftest
echo '*********** ../see-aes128-ofb **************'
../see-aes128-ofb -key xyzzy demo01.aes128ofb .selftest
echo '*********** ../see-aes256-ofb **************'
../see-aes256-ofb -key xyzzy demo01.aes256ofb .selftest
if test -x ../see-aes256-openssl; then
  echo '*********** ../see-aes256-openssl **************'
  ../see-aes256-openssl -key xyzzy demo01.aes256ofb .selftest
fi
echo '*********** ../see-rc4 **************'
../see-rc4 -key xyzzy demo01.rc4 .selftest
echo '*********** ../see-aes128-ccm **************'
../see-aes128-ccm -key xyzzy demo01.aes128ccm .selftest
if test x`uname` = 'xDarwin'; then
  echo '*********** ../see-cccrypt **************'
  ../see-cccrypt -key xyzzy demo01.aes128ofb .selftest
  #                   123456789 123456789 123456789 12
  ../see-cccrypt -key xyzzyxyzzyxyzzyxyzzyxyzzyxyzzyxy demo01.aes256ofb .selftest
fi
