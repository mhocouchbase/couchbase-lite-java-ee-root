/*
** Copyright (c) 2004-2016 Hipp, Wyrick & Company, Inc.
** 6200 Maple Cove Lane, Charlotte, NC 28269 USA
** +1.704.948.4565
**
** All rights reserved.
**
******************************************************************************
**
** Implementation of the database encryption extension to SQLite using
** the CommonCrypto/CommonCryptor.h functionality built into OS-X.
**
** To add this extension to SQLite, append this file onto the end of
** the SQLite amalgamation and then compile with the following additional
** compiler command-line option:
**
**       -DSQLITE_HAS_CODEC=1
*/
#ifdef SQLITE_HAS_CODEC  /* Only compile if SQLITE_HAS_CODEC is set */

#ifndef SQLITE_AMALGAMATION
# error "Compile by appending to the SQLite amalgamation"
#endif

#include <CommonCrypto/CommonCryptor.h>


/*
** In order to allow multiple encryption algorithms to be appended to
** the same amalgamation, rename certain functions if this is not the
** first encryption algorithm on the stack.
*/
#ifdef KEY_SZ
# undef KEY_SZ
# undef AES_BLOCK_SZ
# undef sqlite3CodecAttach
# undef sqlite3CodecGetKey
# undef sqlite3_key
# undef sqlite3_rekey
# define sqlite3CodecAttach  sqlite3CodecAttach_cccryptor
# define sqlite3CodecGetKey  sqlite3CodecGetKey_cccryptor
# define sqlite3_key         sqlite3_key_cccryptor
# define sqlite3_rekey       sqlite3_rekey_cccryptor
#endif

/*
** For an AES-256 version, add the -DCCCRYPT256 compile-time option.
*/
#ifdef CCCRYPT256
# define KEY_SZ          32           /* Size of the AES key in bytes */
# define AES_BLOCK_SZ    16           /* Size of each encryption block */
#else
# define KEY_SZ          16           /* Size of the AES key in bytes */
# define AES_BLOCK_SZ    16           /* Size of each encryption block */
#endif

/*
** State information used by the codec
*/
typedef struct CodecCCCrypto CodecCCCrypto;
struct CodecCCCrypto {
  struct KeyCCCrypto {
    u8 nByte;                    /* Number of bytes in key */
    u8 nullKey;                  /* Do not encrypt if true */
    unsigned char repeat[KEY_SZ];   /* Key text */
  } key[2];                      /* 2 keys.  0: new, 2: old */
  u8 nullKey;                    /* True if mask[] is zero */
  u8 nonceUsable;                /* Amount of usable nonce per page */
  u8 mallocFailed;               /* True if a memory allocation has been seen */
  int nonceZero;                 /* Add this many zeros to end of nonce */
  u32 pageSize;                  /* Amount of data per page */
  u32 usable;                    /* pageSize - reserved-bytes */
  u8 *pZero;                     /* A page of all zeros */
  u8 *mask;                      /* Cached mask.  Space obtained from malloc */
  u8 *outbuf;                    /* Temporary output buffer */
};

/*
** Make sure "strings" turns up a copyright notice
*/
const char sqlite3_Copyright_CCCrypto[] = 
  "Copyright 2004-2016 Hipp, Wyrick & Company, Inc. "
  "6200 Maple Cove Lane "
  "Charlotte, NC 28269 "
  "+1.704.949.4565 "
  "*** Use of this software requires an appropriate license ***";
/*
** XOR buffer b into buffer a.
*/
static void xorBuffers(u8 *out, u8 *a, u8 *b, int nByte){
  if( sizeof(a)==8
   && (out - (u8*)0)%8==0 
   && (a - (u8*)0)%8==0
   && (b - (u8*)0)%8==0
  ){
    u64 *x = (u64*)out;
    u64 *y = (u64*)a;
    u64 *z = (u64*)b;
    while( nByte>8 ){
      *(x++) = *(y++) ^ *(z++);
      nByte -= 8;
    }
    out = (u8*)x;
    a = (u8*)y;
    b = (u8*)z;
  }
  if( (a - (unsigned char*)0)%4==0
   && (b - (unsigned char*)0)%4==0
  ){
    u32 *x = (u32*)out;
    u32 *y = (u32*)a;
    u32 *z = (u32*)b;
    while( nByte>4 ){
      *(x++) = *(y++) ^ *(z++);
      nByte -= 4;
    }
    out = (u8*)x;
    a = (u8*)y;
    b = (u8*)z;
  }
  while( nByte-- > 0 ){
    *(out++) = *(a++) ^ *(b++);
  }
}

/*
** This routine is called to encode or decode a page of data after it is
** read from the main database or journal and before it is written to the
** main database or journal.
**
** op bit 0:     Use key 1 instead of key 0
** op bit 1:     Recompute the mask
** op bit 2:     Recompute nonce (encode)
**
**    op==0:     Use the existing mask (decode)
**    op==2:     Recompute mask using key 0 (decode)
**    op==3:     Recompute mask using key 1 (decode)
**    op==6:     Recompute mask and nonce using key 0 (encode)
**    op==7:     Recompute mask and nonce using key 1 (encode)
*/
static void *sqliteCodecCCCrypto(
  void *codecState,
  void *pageData,
  Pgno pgno,
  int op
){
  CodecCCCrypto *pCodec = codecState;
  unsigned char *zData = pageData;
  unsigned char *a, *b, *out;
  unsigned int i;
  unsigned char nonce[AES_BLOCK_SZ];
  static int one = 1;                   /* For testing endedness */

#if 0
  printf("CODEC %s pgno=%d key=%d op=%d\n", (op&4)==0 ? "decode" : "encode",
     pgno, op&1, op);
#endif


  /* Sanity checking */   
  assert( op>=0 && op<=7 );
  assert( (op&4)==0 || (op&2)!=0 );

  /* Check to see if there is suppose to be any encryption.  Early
  ** out if not
  */
  if( op==0 ){
    if( pCodec->nullKey ) return pageData;
  }else{
    if( pCodec->key[op&1].nullKey ){
      pCodec->nullKey = 1;
      return pageData;
    }
    pCodec->nullKey = 0;
  }

  /* Find the page size and nonce size and allocate space for the mask
  ** cache on the first invocation of the codec.
  */
  if( pCodec->mask==0 ){
    if( pCodec->mallocFailed ) return 0;
    pCodec->mask = sqlite3_malloc( pCodec->pageSize*3 );
    if( pCodec->mask==0 ){
      pCodec->mallocFailed = 1;
      return 0;
    }else{
      pCodec->outbuf = &pCodec->mask[pCodec->pageSize];
      pCodec->pZero = &pCodec->outbuf[pCodec->pageSize];
      memset(pCodec->pZero, 0, pCodec->pageSize);
    }
  }

  /* Find the nonce */
  memcpy(nonce, &pgno, sizeof(pgno));
  if( 0==*(const char*)&one ){
    /* This code executes only if the machine is big-endian.  Byte swap
    ** the page number for compatibility with little-endian machines.
    */
    char t;
    assert( sizeof(pgno)==4 );
    t = nonce[0];
    nonce[0] = nonce[3];
    nonce[3] = t;
    t = nonce[1];
    nonce[1] = nonce[2];
    nonce[2] = t;
  }
  assert( pCodec->nonceUsable + pCodec->nonceZero == AES_BLOCK_SZ - 4 );
  if( pCodec->nonceUsable>0 ){
    if( op&4 ){
      /* Compute a new random nonce value */
      sqlite3_randomness(pCodec->nonceUsable, &nonce[sizeof(pgno)]);
      memcpy(&zData[pCodec->usable], &nonce[sizeof(pgno)], pCodec->nonceUsable);
    }else{
      /* Copy the nonce out of the existing page */
      memcpy(&nonce[sizeof(pgno)], &zData[pCodec->usable], pCodec->nonceUsable);
    }
  }
  if( pCodec->nonceZero>0 ){
    memset(&nonce[AES_BLOCK_SZ-pCodec->nonceZero], 0, pCodec->nonceZero);
  }

  /* Recompute an XOR mask.
  */
  if( op&2 ){
    int nKey = 16;
#ifdef CCCRYPT256
    if( pCodec->key[op&1].nByte==32 ) nKey = 32;
#endif
    CCCrypt(kCCEncrypt, kCCAlgorithmAES, 0, 
            pCodec->key[op&1].repeat, nKey,
            nonce, pCodec->pZero, pCodec->pageSize,
            pCodec->mask, pCodec->pageSize,
            0);
  }

  /* Run the XOR mask against the page data */
  a = zData;
  b = pCodec->mask;
  if( op & 4 ){
    out = pCodec->outbuf;
  }else{
    out = a;
  }
  xorBuffers(out, a, b, pCodec->usable);
  if( pCodec->usable < pCodec->pageSize ){
    memmove(&out[pCodec->usable], &a[pCodec->usable], 
           pCodec->pageSize - pCodec->usable);
  }

#if 0
  printf("CODEC %s pgno=%d key=%d op=%d "
         "nonce=%02x%02x%02x%02x header=%02x%02x%02x%02x%02x%02x\n",
     (op&4)==0 ? "decode" : "encode",
     pgno, op&1, op,
     nonce[0], nonce[1], nonce[2], nonce[3],
     a[0], a[1], a[2], a[3], a[4], a[5], a[6]
  );
#endif


  /* Do not encrypt bytes 16-23 of page 1.  Those bytes contain the page
  ** size information needed to initialize the pager.
  */
  if( pgno==1 ){
    for(i=16; i<=23; i++){  
      out[i] ^= b[i];
    }
  }
  return (void*)out;
}

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
** Load key 0 with the given key.
*/
static void loadKeyCCCrypt(CodecCCCrypto *pCodec, const void *pKey, int nKey){
  if( pKey && nKey!=0 ){
    unsigned const char *zKey = (unsigned const char*)pKey;
    int i;
#ifdef DEMO_ONLY
    zKey = "demo";
    nKey = 4;
#endif
    if( nKey<0 ){
      pwHash(pKey, KEY_SZ, pCodec->key[0].repeat);
      nKey = KEY_SZ;
    }else{
      if( nKey>KEY_SZ ) nKey = KEY_SZ;
      for(i=0; i<KEY_SZ; i++){
        pCodec->key[0].repeat[i] = zKey[i%nKey];
      }
    }
    pCodec->key[0].nByte = nKey;
    pCodec->key[0].nullKey = 0;
  }else{
    pCodec->key[0].nByte = 0;
    pCodec->key[0].nullKey = 1;
  }
}

/*
** Record the current page size
*/
static void sqliteCodecCCCryptoSizeChng(void *p, int pageSize, int nReserve){
  CodecCCCrypto *pCodec = (CodecCCCrypto*)p;
  pCodec->pageSize = pageSize;
  if( nReserve>AES_BLOCK_SZ-4 ){
    pCodec->nonceUsable = AES_BLOCK_SZ-4;
  }else{
    pCodec->nonceUsable = nReserve;
  }
  pCodec->usable = pageSize - nReserve;
  pCodec->nonceZero = AES_BLOCK_SZ - sizeof(Pgno) - pCodec->nonceUsable;
  assert( pCodec->nonceZero>=0 );
  assert( pageSize>=512 && pageSize<=65536 && (pageSize&(pageSize-1))==0 );
}

/*
** Deallocate a codec.
*/
static void sqliteCodecCCCryptoFree(void *p){
  CodecCCCrypto *pCodec = (CodecCCCrypto*)p;
  sqlite3_free(pCodec->mask);
  memset(p, 0, sizeof(*pCodec));
  sqlite3_free(p);
}

/*
** Attach an codec to database iDb in the given connection.
**
** This routine attempts to configure the database so that it has 12 extra
** bytes at the end of each page to store the key nonce.  If it is unsuccessful
** at this, no nonce will be used.  That means that the encryption will
** be vulnerable to a chosen-plantext attach.
*/
int sqlite3CodecAttach(
  sqlite3 *db,       /* The overall sqlite connection */
  int iDb,           /* The particular file within db to which to attach */
  const void *pKey,  /* The key.  May be NULL */
  int nKey           /* The key size */
){
  struct Db *pDb;
  Pager *pPager;
  CodecCCCrypto *pCodec;

  assert( db!=0 );
  assert( iDb>=0 && iDb<db->nDb );
  pDb = &db->aDb[iDb];
  if( pDb->pBt && (pPager = sqlite3BtreePager(pDb->pBt))!=0 ){
    pCodec = sqlite3_malloc( sizeof(CodecCCCrypto) );
    if( pCodec==0 ){
      return SQLITE_NOMEM;
    }
    memset(pCodec, 0, sizeof(CodecCCCrypto));
    /* 12 bytes of nonce for AES */
    sqlite3BtreeSetPageSize(pDb->pBt, 0, AES_BLOCK_SZ-sizeof(Pgno), 0);
    loadKeyCCCrypt(pCodec, pKey, nKey);
    pCodec->key[1] = pCodec->key[0];
    sqlite3PagerSetCodec(pPager,
         sqliteCodecCCCrypto,
         sqliteCodecCCCryptoSizeChng,
         sqliteCodecCCCryptoFree,
         pCodec
    );
  }
  return SQLITE_OK;
}

/*
** Retrive the key from a codec.
*/
void sqlite3CodecGetKey(sqlite3 *db, int iDb, void **ppKey, int *pnKey){
  Db *pDb;
  Pager *pPager;
  CodecCCCrypto *pCodec;
  assert( db!=0 );
  assert( iDb>=0 && iDb<db->nDb );
  pDb = &db->aDb[iDb];
  pPager = sqlite3BtreePager(pDb->pBt);
  pCodec = (CodecCCCrypto*)sqlite3PagerGetCodec(pPager);
  if( pCodec==0 ){
    *pnKey = 0;
    *ppKey = 0;
  }else{
    *pnKey = pCodec->key[0].nByte;
    *ppKey = pCodec->key[0].repeat;
  }
}

/*
** Default behavior is for this library to be statically linked.
*/
#ifndef SQLITE_DLL
# define SQLITE_DLL 0
#endif

/*
** If an activation phrase is supplied, then the following API must
** be invoked to enable the encryption features.  Unless encryption
** activated, none of the encryption routines will work.
*/
#ifndef SEEN_ENABLE_SEE
#define SEEN_ENABLE_SEE 1
static int encryptionEnabled = !SQLITE_DLL;
SQLITE_API void sqlite3_activate_see(const char *zPassPhrase){
  encryptionEnabled = strcmp(zPassPhrase, "7bb07b8d471d642e")==0;
}
#endif /* SEEN_ENABLE_SEE */
 
/*
** Convert a database name (ex: "main", "aux1") into an index into the
** db->aDb[] array.  Return -1 if not found.
*/
static int sqlite3NameToDb(sqlite3 *db, const char *zDbName){
  int i;
  if( zDbName==0 ) return 0;
  for(i=0; i<db->nDb; i++){
    if( db->aDb[i].pBt && sqlite3StrICmp(zDbName, db->aDb[i].zDbSName)==0 ){
      return i;
    }
  }
  return -1;
}

/*
** Set the key on a database.  This routine should be called immediately
** after sqlite3_open().
**
** For a database that is initialially unencrypted but which might
** be encrypted at some future time, call this routine with pKey==0 and
** nKey==0.  Doing so will reserve space on each page for a nonce value
** that will improve the quality of the encryption if the database is
** ever encrypted in the future.
*/
SQLITE_API int sqlite3_key_v2(
  sqlite3 *db,                   /* Database to be decrypted */
  const char *zDbName,           /* Which database to key */
  const void *pKey, int nKey     /* The new key */
){
  int iDb;
  int rc = SQLITE_OK;

#ifdef SQLITE_KEYSIZE_LIMIT
  if( nKey>SQLITE_KEYSIZE_LIMIT ) nKey = SQLITE_KEYSIZE_LIMIT;
#endif
  if( !encryptionEnabled ){
    return SQLITE_MISUSE;
  }
  sqlite3_mutex_enter(db->mutex);
  sqlite3BtreeEnterAll(db);
  iDb = sqlite3NameToDb(db, zDbName);
  if( iDb<0 ){
    rc = SQLITE_ERROR;
  }else{
    rc = sqlite3CodecAttach(db, iDb, pKey, nKey);
  }
  sqlite3BtreeLeaveAll(db);
  sqlite3_mutex_leave(db->mutex);
  return rc;
}
SQLITE_API int sqlite3_key(
  sqlite3 *db,                   /* Database to be decrypted */
  const void *pKey, int nKey     /* The new key */
){
  return sqlite3_key_v2(db, 0, pKey, nKey);
}

/*
** Change the key on an open database.  If the current database is not
** encrypted, this routine will encrypt it.  If pNew==0 or nNew==0, the
** database is decrypted.
**
** The code to implement this API is not available in the public release
** of SQLite.
*/
SQLITE_API int sqlite3_rekey_v2(
  sqlite3 *db,                   /* Database to be rekeyed */
  const char *zDbName,           /* Name of database */
  const void *pKey, int nKey     /* The new key */
){
  Pager *pPager;
  DbPage *pDBPage0 = 0, *pDBPage;
  int rc;
  int nPage, i, iDb;
  CodecCCCrypto *pCodec;
  Db *pDb;
  int skipThisPage = 0;   /* Unencrypted page to be skipped */
  int eState;             /* Initial pager state */

#ifdef SQLITE_KEYSIZE_LIMIT
  if( nKey>SQLITE_KEYSIZE_LIMIT ) nKey = SQLITE_KEYSIZE_LIMIT;
#endif
  if( !encryptionEnabled ){
    db->errCode = SQLITE_MISUSE;
    return SQLITE_MISUSE;
  }
  sqlite3_mutex_enter(db->mutex);
  sqlite3BtreeEnterAll(db);
  iDb = sqlite3NameToDb(db, zDbName);
  if( iDb<0 ){
    rc = SQLITE_ERROR;
    goto end_rekey;
  }
  pDb = &db->aDb[iDb];
  pPager = sqlite3BtreePager(pDb->pBt);
  if( pPager->readOnly ){
    rc = SQLITE_READONLY;
    goto end_rekey;
  }
  pCodec = (CodecCCCrypto*)sqlite3PagerGetCodec(pPager);
  if( pCodec==0 ){
    sqlite3CodecAttach(db, 0, "", 0);
    pCodec = (CodecCCCrypto*)sqlite3PagerGetCodec(pPager);
    if( pCodec==0 ){
      rc = SQLITE_NOMEM;
      goto end_rekey;
    }
  }
  eState = sqlite3PagerState(pPager);
  if( eState==PAGER_OPEN ){
    rc = sqlite3PagerSharedLock(pPager);
    if( rc ) goto end_rekey;
  }
  rc = sqlite3PagerGet(pPager, 1, &pDBPage0, 0);
  if( rc==SQLITE_OK && eState<=PAGER_READER ){
    rc = sqlite3PagerBegin(pPager, 1, 0);
    if( rc!=SQLITE_OK ){
      goto end_rekey;
    }
  }
  if( rc==SQLITE_OK ){
    sqlite3PagerPagecount(pPager, &nPage);
  }else{
    nPage = 0;
  }
  loadKeyCCCrypt(pCodec, pKey, nKey);
  if( nPage ){
    skipThisPage = PENDING_BYTE/pCodec->pageSize + 1;
  }
  for(i=1; i<=nPage && rc==SQLITE_OK; i++){
    if( i==skipThisPage ) continue;
    rc = sqlite3PagerGet(pPager, i, &pDBPage, 0);
    if( rc!=SQLITE_OK ) break;
    if( db->u1.isInterrupted ){
      db->u1.isInterrupted = 0;
      rc = SQLITE_INTERRUPT;
      break;
    }
    rc = sqlite3PagerWrite(pDBPage);
    sqlite3PagerUnref(pDBPage);
  }
  if( rc==SQLITE_OK ){
    pCodec->key[1] = pCodec->key[0];
    if( eState<=PAGER_READER ){
      rc = sqlite3PagerCommitPhaseOne(pPager, 0, 0);
      if( rc==SQLITE_OK ) rc = sqlite3PagerCommitPhaseTwo(pPager);
    }
  }else{
    pCodec->key[0] = pCodec->key[1];
    sqlite3PagerRollback(pPager);
  }

end_rekey:
  if( pDBPage0 )sqlite3PagerUnrefPageOne(pDBPage0);
  sqlite3BtreeLeaveAll(db);
  sqlite3_mutex_leave(db->mutex);
  if( rc ) db->errCode = rc;
  return rc;
}
SQLITE_API int sqlite3_rekey(
  sqlite3 *db,                   /* Database to be rekeyed */
  const void *pKey, int nKey     /* The new key */
){
  return sqlite3_rekey_v2(db, 0, pKey, nKey);
}

#endif /* SQLITE_HAS_CODEC */
