//
// Copyright (c) 2018 Couchbase, Inc.  All rights reserved.
//
// Licensed under the Couchbase License Agreement (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
// https://info.couchbase.com/rs/302-GJY-034/images/2017-10-30_License_Agreement.pdf
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
package com.couchbase.lite;

import android.support.annotation.NonNull;

import com.couchbase.lite.internal.core.C4Constants;
import com.couchbase.lite.internal.core.C4Key;
import com.couchbase.lite.internal.utils.Preconditions;


/**
 * <b>ENTERPRISE EDITION API</b><br><br>
 * <p>
 * An encryption key for a database. This is a symmetric key that be kept secret.
 * It should be stored either in the Keychain, or in the user's memory (hopefully not a sticky note.)
 */
public final class EncryptionKey {
    private final byte[] key;

    /**
     * Initializes the encryption key with a raw AES-128 key 16 bytes in length.
     * To create a key, generate random data using a secure cryptographic randomizer.
     * <p>
     *
     * @param key The raw AES-128 key data.
     */
    public EncryptionKey(@NonNull byte[] key) {
        Preconditions.assertNotNull(key, "key");
        if (key.length != C4Constants.EncryptionKeySize.AES256) {
            throw new IllegalArgumentException("Key size is invalid. Key must be a 256-bit (32-byte) key.");
        }
        this.key = new byte[C4Constants.EncryptionKeySize.AES256];
        System.arraycopy(key, 0, this.key, 0, C4Constants.EncryptionKeySize.AES256);
    }

    /**
     * Initializes the encryption key from the given password string.
     * The password string will be internally converted to a raw AES-128 key using 64,000 rounds of PBKDF2 hashing.
     *
     * @param password The password string.
     */
    public EncryptionKey(@NonNull String password) {
        Preconditions.assertNotNull(password, "password");

        // This is wrong; can't change the API.
        final byte[] key;
        try { key = C4Key.getPbkdf2Key(password); }
        catch (CouchbaseLiteException e) { throw new IllegalArgumentException(e.getMessage(), e); }

        this.key = key;
    }

    byte[] getKey() {
        final byte[] key = new byte[this.key.length];
        System.arraycopy(this.key, 0, key, 0, key.length);
        return key;
    }
}
