/*
** Copyright (c) 2004-2008 Hipp, Wyrick & Company, Inc.
** 6200 Maple Cove Lane, Charlotte, NC 28269 USA
** +1.704.948.4565
**
** All rights reserved.
**
******************************************************************************
**
** This SQLite extension implements the function cryptoapi_decrypt(X,Y).
**
** If X is a BLOB that was encrypted using the legacy CryptoAPI-based codec
** that was included with System.Data.SQLite, attempt to decrypt it using a
** key derived from BLOB password Y and return the result.  If either of the
** arguments are not BLOBs, return NULL.  If X is either NULL or zero bytes
** in length, this function is a no-op.  If decryption cannot be completed,
** the result will be an error containing an appropriate message.
**
** To compile this extension using MSVC, a command similar to the following
** should be used:
**
** cl.exe cryptoapi.c -DSQLITE_OS_WIN -I. -Fecryptoapi.dll -link -dll advapi32.lib
*/
#include "sqlite3ext.h"
SQLITE_EXTENSION_INIT1

#ifdef SQLITE_OS_WIN
#include <windows.h>
#include <assert.h>
#include <string.h>

/*
** Forward declaration of objects used by this implementation.
*/
typedef struct cryptoapi_ctx cryptoapi_ctx;

/*
** The context information that will be passed into the functions that are
** implemented by this extension.
*/
struct cryptoapi_ctx {
  HCRYPTPROV hProv; /* CryptoAPI provider context */
  BOOL bEncrypt;    /* Non-zero for encryption, zero for decryption */
};

/*
** cryptoapi_decrypt(X,Y): If X is a BLOB that was encrypted using the legacy
** CryptoAPI-based codec that was included with System.Data.SQLite, attempt
** to decrypt it using a key derived from BLOB password Y and return the
** result.  If either of the arguments are not BLOBs, return NULL.  If X is
** either NULL or zero bytes in length, this function is a no-op.  If
** decryption cannot be completed, the result will be an error containing
** an appropriate message.
**
** cryptoapi_encrypt(X,Y): Attempt to encrypt BLOB data X using a key derived
** from BLOB password Y and return the result.  If either of the arguments are
** not BLOBs, return NULL.  If X is either NULL or zero bytes in length, this
** function is a no-op.  If encryption cannot be completed, the result will be
** an error containing an appropriate message.
*/
static void cryptoapiFunc(
  sqlite3_context *context,
  int argc,
  sqlite3_value **argv
){
  cryptoapi_ctx *pCtx = (cryptoapi_ctx *)sqlite3_user_data(context);
  if( !pCtx ){
    sqlite3_result_error(context, "missing encryption context", -1);
    return;
  }
  assert( argc==2 );
  (void)argc;
  if( sqlite3_value_type(argv[0])==SQLITE_BLOB
   && sqlite3_value_type(argv[1])==SQLITE_BLOB ){
    LPCBYTE pOldData = sqlite3_value_blob(argv[0]);
    if( pOldData ){
      DWORD nOldData = (DWORD)sqlite3_value_bytes(argv[0]);
      if( nOldData>0 ){
        LPCBYTE zPassword = sqlite3_value_blob(argv[1]);
        DWORD nPassword = (DWORD)sqlite3_value_bytes(argv[1]);
        HCRYPTHASH hHash = 0;
        HCRYPTKEY hKey = 0;
        LPBYTE pNewData = 0;
        DWORD nNewData = 0;
        char *zMsg = 0;
        if( !CryptCreateHash(pCtx->hProv, CALG_SHA1, 0, 0, &hHash) ){
          zMsg = sqlite3_mprintf("CryptCreateHash failed, code=%lu",
                                 GetLastError());
          sqlite3_result_error(context, zMsg, -1);
          goto done;
        }
        if( !CryptHashData(hHash, zPassword, nPassword, 0) ){
          zMsg = sqlite3_mprintf("CryptHashData failed, code=%lu",
                                 GetLastError());
          sqlite3_result_error(context, zMsg, -1);
          goto done;
        }
        if( !CryptDeriveKey(pCtx->hProv, CALG_RC4, hHash, 0, &hKey) ){
          zMsg = sqlite3_mprintf("CryptDeriveKey failed, code=%lu",
                                 GetLastError());
          sqlite3_result_error(context, zMsg, -1);
          goto done;
        }
        pNewData = sqlite3_malloc64(nOldData);
        if( !pNewData ){
          sqlite3_result_error_nomem(context);
          goto done;
        }
        assert( nOldData<=((size_t)-1) );
        memcpy(pNewData, pOldData, (size_t)nOldData);
        nNewData = nOldData;
        if( pCtx->bEncrypt ){
          if( !CryptEncrypt(hKey, 0, TRUE, 0, pNewData, &nNewData, nOldData) ){
            zMsg = sqlite3_mprintf("CryptEncrypt failed, code=%lu",
                                   GetLastError());
            sqlite3_result_error(context, zMsg, -1);
            goto done;
          }
        }else{
          if( !CryptDecrypt(hKey, 0, TRUE, 0, pNewData, &nNewData) ){
            zMsg = sqlite3_mprintf("CryptDecrypt failed, code=%lu",
                                   GetLastError());
            sqlite3_result_error(context, zMsg, -1);
            goto done;
          }
        }
        assert( nNewData<=0x7fffffff );
        sqlite3_result_blob(context, pNewData, nNewData, SQLITE_TRANSIENT);
      done:
        sqlite3_free(pNewData);
        sqlite3_free(zMsg);
        if( hKey ) CryptDestroyKey(hKey);
        if( hHash ) CryptDestroyHash(hHash);
      }else{
        sqlite3_result_zeroblob(context, 0);
      }
    }
  }
}

/*
** Frees resources that were successfully allocated.
*/
static void freeContext(
  void *pUserData /* Pointer to context structure. */
){
  cryptoapi_ctx *pCtx = (cryptoapi_ctx *)pUserData;
  if( pCtx ){
    if( pCtx->hProv ){
      if( !pCtx->bEncrypt ){ /* shared, release only once */
        CryptReleaseContext(pCtx->hProv, 0);
      }
      pCtx->hProv = 0;
    }
    sqlite3_free(pCtx);
  }
}
#endif

#ifdef _WIN32
__declspec(dllexport)
#endif
int sqlite3_cryptoapi_init(
  sqlite3 *db,
  char **pzErrMsg,
  const sqlite3_api_routines *pApi
){
  int rc = SQLITE_OK;
#ifdef SQLITE_OS_WIN
  cryptoapi_ctx *pCtx1, *pCtx2;
  HCRYPTPROV hProv = 0;
#endif
  SQLITE_EXTENSION_INIT2(pApi);
#ifdef SQLITE_OS_WIN
  pCtx1 = sqlite3_malloc( sizeof(cryptoapi_ctx) );
  if( pCtx1==0 ) return SQLITE_NOMEM;
  memset( pCtx1, 0, sizeof(cryptoapi_ctx) );
  pCtx2 = sqlite3_malloc( sizeof(cryptoapi_ctx) );
  if( pCtx2==0 ){ freeContext(pCtx1); return SQLITE_NOMEM; }
  memset( pCtx2, 0, sizeof(cryptoapi_ctx) );
  if( !CryptAcquireContext(&hProv, NULL, MS_ENHANCED_PROV,
                           PROV_RSA_FULL, CRYPT_VERIFYCONTEXT) ){
    *pzErrMsg = sqlite3_mprintf("CryptAcquireContext failed, code=%lu",
                                GetLastError());
    freeContext(pCtx2); freeContext(pCtx1);
    return SQLITE_ERROR;
  }
  pCtx1->hProv = hProv;
  pCtx1->bEncrypt = 0;
  rc = sqlite3_create_function_v2(db, "cryptoapi_decrypt", 2, SQLITE_UTF8,
                                  pCtx1, cryptoapiFunc, 0, 0, freeContext);
  if( rc==SQLITE_OK ){
    pCtx2->hProv = hProv;
    pCtx2->bEncrypt = 1;
    rc = sqlite3_create_function_v2(db, "cryptoapi_encrypt", 2, SQLITE_UTF8,
                                    pCtx2, cryptoapiFunc, 0, 0, freeContext);
  }
#else
  (void)pzErrMsg;  /* Unused parameter */
#endif
  return rc;
}
