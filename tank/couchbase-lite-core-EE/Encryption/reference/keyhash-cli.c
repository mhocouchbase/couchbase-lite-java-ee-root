/*
** When a long passphrase is handed to sqlite3_key() or sqlite3_key_v2()
** (by making the pKey argument point to a zero-terminated string and
** setting nKey to -1) then the key used in the encryption algorithm is
** derived from the passphrase using a hash function.
**
** This routine shows what the encryption key would be given a
** passphrase.
*/
#include <stdio.h>
#include <string.h>

/*
** Given an arbitrary-length string key (password or passphrase) generate
** an N-byte binary key that tries to capture as much of the entropy in
** the input passphrase as possible.
**
** This routine is NOT a cryptographic hash.  It is not intended to be
** irreversible.  The purpose of this hash is to compress the entropy in
** a longer text key down into a shorter binary key.
*/
static void pwHash(const char *zPassPhrase, int N, unsigned char *aBuf){
  unsigned char i, j, t;
  int m, n;
  unsigned char s[256];
  for(m=0; m<256; m++){ s[m] = m; }
  if( zPassPhrase[0] ){
    for(j=0, m=n=0; m<256; m++, n++){
      j += s[m] + zPassPhrase[n];
      if( zPassPhrase[n]==0 ){ n = -1; }
      t = s[j];
      s[j] = s[m];
      s[m] = t;
    }
  }
  i = j = 0;
  for(n=0; n<N; n++){
    i++;
    t = s[i];
    j += t;
    s[i] = s[j];
    s[j] = t;
    aBuf[n] = t + s[i];
  }
}

/*
** Available algorithms.
*/
#define SEE_RC4          0
#define SEE_AES128OFB    1
#define SEE_AES256OFB    2
#define SEE_AES128CCM    3

/*
** Keysize based on algorithm.
*/
static const int szKey[] = { 256, 16, 32, 16 };


/*
** Print out hexadecimal showing the encryption key given an input
** passphrase.
*/
static void showKey(const char *zKey){
  size_t nKey = strlen(zKey);
  int alg = SEE_AES128OFB;
  int useHash = 1;
  int i;
  int sz;
  int nPrefix = 0;
  unsigned char key[300];
  if( nKey>256 ) nKey = 256;
  if( nKey>4 && memcmp(zKey, "rc4:", 4)==0 ){
    nPrefix = 4;
    alg = SEE_RC4;
    useHash = 0;
  }else if( nKey>7 && memcmp(zKey, "aes128:", 7)==0 ){
    nPrefix = 7;
    alg = SEE_AES128OFB;
  }else if( nKey>7 && memcmp(zKey, "aes256:", 7)==0 ){
    nPrefix = 7;
    alg = SEE_AES256OFB;
  }
  zKey += nPrefix;
  nKey -= nPrefix;
  sz = szKey[alg];
  if( useHash ){
    pwHash(zKey, sz, key);
  }else{
    if( nKey>sz ) nKey = sz;
    for(i=0; i<sz; i++) key[i] = zKey[i%nKey];
  }
  for(i=0; i<sz; i++) printf("%02x", key[i]); 
  printf("\n");
}

/*
** Prompt for pass-phrases and output the resulting key functions in hex.
*/
int main(int argc, char **argv){
  size_t n;
  char zLine[1000];
  while( fgets(zLine, sizeof(zLine), stdin) ){
    n = strlen(zLine);
    while( n>0 && (zLine[n-1]=='\n' || zLine[n-1]=='\r') ){ n--; }
    zLine[n] = 0;
    showKey(zLine);
  }
  return 0;
}
