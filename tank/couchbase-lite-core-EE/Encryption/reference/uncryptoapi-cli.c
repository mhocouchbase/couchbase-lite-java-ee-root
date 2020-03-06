/*
** Copyright (c) 2004-2012 Hipp, Wyrick & Company, Inc.
** 6200 Maple Cove Lane, Charlotte, NC 28269 USA
** +1.704.948.4565
**
** All rights reserved.
**
******************************************************************************
**
** Implementation of a database decryption tool that is used to decrypt a
** database encrypted using the System.Data.SQLite legacy CryptoAPI-based
** codec.
**
*/

/*
** Prevent MSVC from raising various spurious compiler warnings due to use
** of ANSI/ISO C runtime functions.
*/
#ifndef _CRT_SECURE_NO_DEPRECATE
#define _CRT_SECURE_NO_DEPRECATE
#endif

#ifndef _CRT_SECURE_NO_WARNINGS
#define _CRT_SECURE_NO_WARNINGS
#endif

#ifndef _CRT_NONSTDC_NO_DEPRECATE
#define _CRT_NONSTDC_NO_DEPRECATE
#endif

#ifndef _CRT_NONSTDC_NO_WARNINGS
#define _CRT_NONSTDC_NO_WARNINGS
#endif

/*
** This value is the minimum possible page size for a database.  Any page size
** less than this is considered to be invalid.
*/
#ifndef MINIMUM_PAGE_SIZE
#define MINIMUM_PAGE_SIZE   (512)
#endif

/*
** This value is the maximum possible page size for a database.  Any page size
** greater than this is considered to be invalid.
*/
#ifndef MAXIMUM_PAGE_SIZE
#define MAXIMUM_PAGE_SIZE   (65536)
#endif

/*
** The string will be appended to the input (encrypted database) file name in
** order to form the output (decrypted database) file name.
*/
#ifndef OUTPUT_FILE_SUFFIX
#define OUTPUT_FILE_SUFFIX  ".out"
#endif

/*
** Routine to read a two-byte big-endian integer value.  This is used to read
** the page size.
*/
#ifndef get2byte
#define get2byte(x)         ((x)[0]<<8 | (x)[1])
#endif

/*
** The CryptoAPI (Win32) is required; therefore, include the necessary Windows
** header files.  Also, include the necessary headers for the C runtime library
** functions in use.
*/
#include "windows.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/*
** Define the types used by the decryptor.  The Pgno type must be wide enough
** to contain the largest valid page number.  The DecryptorContext context is
** used to hold all the resources allocated during the decryption process.
*/
typedef unsigned int Pgno; /* at least 32-bit */
typedef struct DecryptorContext DecryptorContext;

struct DecryptorContext {
  char *zPassword;    /* password bytes used to derive key */
  size_t nPassword;   /* number of bytes in the password */
  char *zInFileName;  /* file name of the encrypted (input) database */
  char *zOutFileName; /* file name of the decrypted (output) database */

  FILE *hInFile;      /* stream for the encrypted (input) database */
  FILE *hOutFile;     /* stream for the decrypted (output) database */

  HCRYPTPROV hProv;   /* CryptoAPI provider context */
  HCRYPTHASH hHash;   /* CryptoAPI hash context */
  HCRYPTKEY hKey;     /* CryptoAPI key context */

  size_t pageSize;    /* size of a database page, in bytes */
  Pgno iCurPg;        /* current database page number */
  size_t nRead;       /* number of bytes last read from input stream */
  size_t nWrote;      /* number of bytes last written to output stream */

  LPBYTE pBuf;        /* buffer used to read/decrypt/write a single page */
  BYTE aBuf[MINIMUM_PAGE_SIZE]; /* buffer used to read first 512 bytes */
};

/*
** Emits a formatted error message to the standard error stream.  The value
** pointed to by pRc will be set to non-zero, if pRc is non-zero.
*/
static void error(
  int *pRc,            /* OUT: optional return-code pointer */
  const char *zFormat, /* IN: printf-style message format */
  ...                  /* IN: optional format string values */
){
  va_list ap;
  va_start(ap, zFormat);
  fprintf(stderr, "ERROR: ");
  vfprintf(stderr, zFormat, ap);
  fprintf(stderr, "\n");
  va_end(ap);
  if( pRc ) *pRc = 1;
}

/*
** Frees resources that were successfully allocated by the decryptor.  The
** value pointed to by pRc will be set to non-zero if this function fails
** for any reason and pRc is non-zero.
*/
static void FreeDecryptorContext(
  DecryptorContext *pCtx, /* IN/OUT: context information to free */
  int *pRc                /* OUT: optional return-code pointer */
){
  if( !pCtx ) return;
  if( pCtx->hKey ){
    if( !CryptDestroyKey(pCtx->hKey) ){
      error(0, "CryptDestroyKey, code=%lu", GetLastError());
      if( pRc && *pRc==0 ) *pRc = 6;
    }
    pCtx->hKey = 0;
  }
  if( pCtx->hHash ){
    if( !CryptDestroyHash(pCtx->hHash) ){
      error(0, "CryptDestroyHash, code=%lu", GetLastError());
      if( pRc && *pRc==0 ) *pRc = 5;
    }
    pCtx->hHash = 0;
  }
  if( pCtx->hProv ){
    if( !CryptReleaseContext(pCtx->hProv, 0) ){
      error(0, "CryptReleaseContext, code=%lu", GetLastError());
      if( pRc && *pRc==0 ) *pRc = 4;
    }
    pCtx->hProv = 0;
  }
  if( pCtx->hOutFile ){
    if( fclose(pCtx->hOutFile)!=0 ){
      error(0, "fclose(output), errno=%d", errno);
      if( pRc && *pRc==0 ) *pRc = 3;
    }
    pCtx->hOutFile = 0;
  }
  if( pCtx->hInFile ){
    if( fclose(pCtx->hInFile)!=0 ){
      error(0, "fclose(input), errno=%d", errno);
      if( pRc && *pRc==0 ) *pRc = 2;
    }
    pCtx->hInFile = 0;
  }
  if( pCtx->pBuf ){
    free(pCtx->pBuf);
    pCtx->pBuf = 0;
  }
  if( pCtx->zOutFileName ){
    free(pCtx->zOutFileName);
    pCtx->zOutFileName = 0;
  }
  if( pCtx->zInFileName ){
    free(pCtx->zInFileName);
    pCtx->zInFileName = 0;
  }
  if( pCtx->zPassword ){
    free(pCtx->zPassword);
    pCtx->zPassword = 0;
  }
  free(pCtx);
}

/*
** Attempts to duplicate the input string zIn, optionally allocating space for
** nExtraChars additional characters.  Both parameters may be zero.  In that
** case, a single byte is still allocated (i.e. an empty string).  The caller
** must free the returned string.  The returned string is guaranteed to be NUL
** terminated.  If memory allocation fails, zero will be returned.
*/
static char *strdup_plus(
  const char *zIn,   /* IN: string to be duplicated */
  size_t nExtraChars /* IN: extra characters to be allocated */
){
  size_t nOldLen = zIn ? strlen(zIn) : 0;       /* length of input string */
  size_t nNewLen = (nOldLen + nExtraChars + 1); /* length of output string */
  char *zNew = calloc(nNewLen, sizeof(char));
  if( !zNew ) return 0;
  if( zIn ) strcpy(zNew, zIn);
  return zNew;
}

/*
** Allocates and returns a copy of the input string with all supported escape
** sequences replaced with their corresponding character values.  The list of
** supported escape sequences is:
**
**                   "\0" --> ASCII 0x00, null character
**                   "\a" --> ASCII 0x07, audible bell
**                   "\b" --> ASCII 0x08, backspace
**                   "\t" --> ASCII 0x09, horizontal tab
**                   "\n" --> ASCII 0x0A, line-feed
**                   "\r" --> ASCII 0x0D, carriage-return
**                   "\v" --> ASCII 0x0B, vertical tab
**                   "\f" --> ASCII 0x0C, form feed
**                   "\s" --> ASCII 0x20, space
**                   "\d" --> ASCII 0x22, double quote
**                   "\q" --> ASCII 0x27, single quote
**                   "\\" --> ASCII 0x5C, backslash
**
** The above list is intended to be the minimal set of characters that permit
** input strings that may contain characters reserved by the operating system
** shell -OR- that cannot be passed verbatim via the operating system command
** line.  Any (unsupported) escape sequence not listed above will simply have
** its preceding backslash stripped.
**
** The string returned from this function will be NUL terminated; however, it
** may also contain embedded NUL characters.  If successful and pOutLen is
** non-zero, it will be set to the actual length of the returned string.  Zero
** will be returned upon failure.
*/
static char *unescape(
  char *zIn,      /* IN: escaped string to be processed */
  size_t *pOutLen /* OUT: final length of returned string */
){
  char *zOutStr;    /* unescaped output string */
  size_t i, j, len; /* input/output indexes and length */
  if( !zIn ){ return 0; }
  len = strlen(zIn);
  zOutStr = calloc(len+1, sizeof(char));
  if( !zOutStr ){ return 0; }
  for(i=0, j=0; i<len; /* no-op */){
    char c = zIn[i];
    if( c=='\\' ){
      size_t k = i+1;
      if( k<len ){
        c = zIn[k];
        switch( c ){
          case '0': c = '\0'; break; /* NULL */
          case 'a': c = '\a'; break; /* BELL */
          case 'b': c = '\b'; break; /* BKSP */
          case 't': c = '\t'; break; /* HTAB */
          case 'n': c = '\n'; break; /*   LF */
          case 'r': c = '\r'; break; /*   CR */
          case 'v': c = '\v'; break; /* VTAB */
          case 'f': c = '\f'; break; /* FORM */
          case 's': c =  ' '; break; /* SPAC */
          case 'd': c =  '"'; break; /* QUOT */
          case 'q': c = '\''; break; /* APOS */
        }
      }
      i += 2;
    }else{
      i++;
    }
    zOutStr[j++] = c;
  }
  zOutStr[len] = '\0';
  if( pOutLen ) *pOutLen = j;
  return zOutStr;
}

/*
** Entry point for the database decryptor tool.  The command line syntax is:
**
**                   uncryptoapi-cli <inputFileName> <escapedPassword>
**
** There must be exactly two arguments.  The first argument is the name of the
** input file name, which must be an encrypted database.  The second argument
** must be the decryption password for the encrypted database.  If it contains
** any characters that cannot be passed verbatim via the operating system
** command line, they must be escaped.  The list of supported escape sequences
** is:
**                   "\0" --> ASCII 0x00, null character
**                   "\a" --> ASCII 0x07, audible bell
**                   "\b" --> ASCII 0x08, backspace
**                   "\t" --> ASCII 0x09, horizontal tab
**                   "\n" --> ASCII 0x0A, line-feed
**                   "\r" --> ASCII 0x0D, carriage-return
**                   "\v" --> ASCII 0x0B, vertical tab
**                   "\f" --> ASCII 0x0C, form feed
**                   "\s" --> ASCII 0x20, space
**                   "\d" --> ASCII 0x22, double quote
**                   "\q" --> ASCII 0x27, single quote
**                   "\\" --> ASCII 0x5C, backslash
**
** Any (unsupported) escape sequence not listed above will simply have its
** preceding backslash stripped.
*/
int main(int argc, char *argv[])
{
  int rc = 0; /* exit code */
  DecryptorContext *pCtx = 0;

  /*
  ** Process and validate the command line arguments.  If any of these steps
  ** fail, processing is halted.
  */
  assert( sizeof(Pgno)>=4 ); /* at least 32-bit */
  if( argc!=3 ){
    error(&rc, "usage: uncryptoapi-cli <inputFileName> <escapedPassword>");
    goto done;
  }
  pCtx = calloc(1, sizeof(DecryptorContext));
  if( !pCtx ){
    error(&rc, "cannot allocate %d bytes", (int)sizeof(DecryptorContext));
    goto done;
  }
  pCtx->zInFileName = strdup_plus(argv[1], 0);
  if( !pCtx->zInFileName ){
    error(&rc, "out of memory (zInFileName)");
    goto done;
  }
  pCtx->zPassword = unescape(argv[2], &pCtx->nPassword);
  if( !pCtx->zPassword ){
    error(&rc, "out of memory (zPassword)");
    goto done;
  }
  if( pCtx->nPassword==0 ){
    error(&rc, "decryption password cannot be an empty string");
    goto done;
  }
  pCtx->hInFile = fopen(pCtx->zInFileName, "rb");
  if( !pCtx->hInFile ){
    error(&rc, "could not open \"%s\" for reading", pCtx->zInFileName);
    goto done;
  }
  pCtx->zOutFileName = strdup_plus(
    pCtx->zInFileName, strlen(OUTPUT_FILE_SUFFIX)
  );
  if( !pCtx->zOutFileName ){
    error(&rc, "out of memory (zOutFileName)");
    goto done;
  }
  strcat(pCtx->zOutFileName, OUTPUT_FILE_SUFFIX);
  pCtx->hOutFile = fopen(pCtx->zOutFileName, "wb");
  if( !pCtx->hOutFile ){
    error(&rc, "could not open \"%s\" for writing", pCtx->zOutFileName);
    goto done;
  }
  /*
  ** Initialize the CryptoAPI provider and hash contexts.  Finally, derive the
  ** decryption key from the specified password bytes.  If any of these steps
  ** fail, processing is halted.
  */
  if( !CryptAcquireContext(&pCtx->hProv, NULL, MS_ENHANCED_PROV,
                           PROV_RSA_FULL, CRYPT_VERIFYCONTEXT) ){
    error(&rc, "CryptAcquireContext, code=%lu", GetLastError());
    goto done;
  }
  if( !CryptCreateHash(pCtx->hProv, CALG_SHA1, 0, 0, &pCtx->hHash) ){
    error(&rc, "CryptCreateHash, code=%lu", GetLastError());
    goto done;
  }
  if( !CryptHashData(pCtx->hHash, (LPBYTE)pCtx->zPassword,
                     (DWORD)pCtx->nPassword, 0) ){
    error(&rc, "CryptHashData, code=%lu", GetLastError());
    goto done;
  }
  if( !CryptDeriveKey(pCtx->hProv, CALG_RC4, pCtx->hHash, 0, &pCtx->hKey) ){
    error(&rc, "CryptDeriveKey, code=%lu", GetLastError());
    goto done;
  }
  /*
  ** Read the first 512 bytes of the input file.  This represents the smallest
  ** possible database page size; therefore, this must succeed for any valid
  ** database file.
  */
  pCtx->nRead = fread(
    pCtx->aBuf, sizeof(BYTE), MINIMUM_PAGE_SIZE, pCtx->hInFile
  );
  if( pCtx->nRead!=MINIMUM_PAGE_SIZE ){
    error(&rc, "cannot read page 1: wanted %d, read %d",
          (int)MINIMUM_PAGE_SIZE, (int)pCtx->nRead);
    goto done;
  }
  /*
  ** Attempt to decrypt the first 512 bytes read from the input file.  This is
  ** important because we need to extract the real page size from the decrypted
  ** data prior to processing any remaining pages.
  */
  if( !CryptDecrypt(pCtx->hKey, 0, TRUE, 0, (LPBYTE)pCtx->aBuf,
                    &pCtx->nRead) ){
    error(&rc, "CryptDecrypt, code=%lu", GetLastError());
    goto done;
  }
  /*
  ** Extract page size from the database header (offset 16) and make sure it
  ** looks valid.  The value must be greater than or equal to 512, less than
  ** or equal to 65536, and an integral power of 2.  If this value is invalid,
  ** it would be very difficult to continue processing reliably.
  */
  pCtx->pageSize = get2byte(&pCtx->aBuf[16]);
  if( pCtx->pageSize<MINIMUM_PAGE_SIZE ){
    error(&rc, "page size %d less than minimum %d",
          (int)pCtx->pageSize, (int)MINIMUM_PAGE_SIZE);
    goto done;
  }
  if( pCtx->pageSize>MAXIMUM_PAGE_SIZE ){
    error(&rc, "page size %d greater than maximum %d",
          (int)pCtx->pageSize, (int)MAXIMUM_PAGE_SIZE);
    goto done;
  }
  if( ((pCtx->pageSize-1)&pCtx->pageSize)!=0 ){
    error(&rc, "page size %d not an integral power of 2",
          (int)pCtx->pageSize);
    goto done;
  }
  /*
  ** Allocate a buffer of the correct size for the extracted page size.  This
  ** will require a maximum of 65536 bytes.
  */
  pCtx->pBuf = calloc(pCtx->pageSize, sizeof(BYTE));
  if( !pCtx->pBuf ){
    error(&rc, "cannot allocate %d bytes", (int)pCtx->pageSize);
    goto done;
  }
  /*
  ** Seek back to the start of the input file.  This is done prior to the main
  ** processing loop to make it easier to handle all the page size chunks.  If
  ** this fails, give up.
  */
  if( fseek(pCtx->hInFile, 0, SEEK_SET)!=0 ){
    error(&rc, "cannot seek to start of input file, errno=%d", errno);
    goto done;
  }
  /*
  ** Starting with the first database page, read a single page worth of bytes
  ** from the input file, decrypt them using the derived key, and write them to
  ** the output file.  This loop assumes that a valid database file must have a
  ** size that is a multiple of the database page size.
  */
  pCtx->iCurPg = 1;
  while( !feof(pCtx->hInFile) ){
    memset(pCtx->pBuf, 0, sizeof(BYTE)*pCtx->pageSize);
    pCtx->nRead = fread(
      pCtx->pBuf, sizeof(BYTE), pCtx->pageSize, pCtx->hInFile
    );
    if( pCtx->nRead==0 ) continue;
    if( pCtx->nRead!=pCtx->pageSize ){
      error(&rc, "cannot read page %d: wanted %d, read %d",
            pCtx->iCurPg, (int)pCtx->pageSize, (int)pCtx->nRead);
      goto done;
    }
    if( !CryptDecrypt(pCtx->hKey, 0, TRUE, 0, (LPBYTE)pCtx->pBuf,
                      &pCtx->nRead) ){
      error(&rc, "CryptDecrypt, page %d, code=%lu", (int)pCtx->iCurPg,
            GetLastError());
      goto done;
    }
    pCtx->nWrote = fwrite(
      pCtx->pBuf, sizeof(BYTE), pCtx->pageSize, pCtx->hOutFile
    );
    if( pCtx->nWrote!=pCtx->pageSize ){
      error(&rc, "cannot write page %d: wanted %d, wrote %d",
            pCtx->iCurPg, (int)pCtx->pageSize, (int)pCtx->nWrote);
      goto done;
    }
    pCtx->iCurPg++;
  }

done:
  /*
  ** Free any resources allocated during the decryption process and return the
  ** exit code.  Upon success, the exit code will be zero.  Upon failure, an
  ** appropriate error message will be emitted to the standard error stream.
  */
  FreeDecryptorContext(pCtx, &rc);
  return rc;
}
