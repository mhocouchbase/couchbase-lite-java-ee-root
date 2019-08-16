//
// EncryptionKey.java
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

import java.nio.charset.StandardCharsets;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import com.couchbase.lite.internal.core.C4Constants;
import com.couchbase.lite.internal.core.C4Key;


/**
 * <b>ENTERPRISE EDITION API</b><br/></br>
 * <p>
 * An encryption key for a database. This is a symmetric key that be kept secret.
 * It should be stored either in the Keychain, or in the user's memory (hopefully not a sticky note.)
 */
public final class EncryptionKey {
    private static final String DEFAULT_PBKDF2_KEY_SALT = "Salty McNaCl";
    private static final int DEFAULT_PBKDF2_KEY_ROUNDS = 64000; // Same as what SQLCipher uses

    private byte[] key;

    /**
     * Initializes the encryption key with a raw AES-128 key 16 bytes in length.
     * To create a key, generate random data using a secure cryptographic randomizer.
     * <p>
     *
     * @param key The raw AES-128 key data.
     */
    // !!! FIXME: This method stores a mutable array as private data
    @SuppressFBWarnings("EI_EXPOSE_REP")
    public EncryptionKey(@NonNull byte[] key) {
        if (key == null) { throw new IllegalArgumentException("key cannot be null."); }
        if (key.length != C4Constants.EncryptionKeySize.AES256) {
            throw new IllegalArgumentException("Key size is invalid. Key must be a 256-bit (32-byte) key.");
        }
        this.key = key;
    }

    /**
     * Initializes the encryption key with the given password string. The password string will be
     * internally converted to a raw AES-128 key using 64,000 rounds of PBKDF2 hashing.
     *
     * @param password The password string.
     */
    public EncryptionKey(String password) {
        this(password == null
            ? null
            : C4Key.pbkdf2(
                password,
                DEFAULT_PBKDF2_KEY_SALT.getBytes(StandardCharsets.UTF_8),
                DEFAULT_PBKDF2_KEY_ROUNDS,
                C4Constants.EncryptionKeySize.AES256));
    }

    // !!! FIXME: This method returns a writeable copy of its private data
    @SuppressFBWarnings("EI_EXPOSE_REP")
    byte[] getKey() {
        return key;
    }
}
