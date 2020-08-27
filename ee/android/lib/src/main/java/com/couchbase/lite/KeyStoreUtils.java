//
// Copyright (c) 2020, 2017 Couchbase, Inc All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
package com.couchbase.lite;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableEntryException;
import java.security.cert.CertificateException;

import com.couchbase.lite.internal.utils.Volatile;


/**
 * Key Store Utilities
 */
@Volatile
public final class KeyStoreUtils {
    private KeyStoreUtils() {}

    public static final String ANDROID_KEY_STORE = "AndroidKeyStore";

    /**
     * This utility copies an entry (public and private keys and any certificates)
     * from the passed keystore stream into Android's canonical keystore,
     * where it can be used as a TlsIdentity.
     * <p>
     * NOTE!
     * This method copies the private key into main memory.  This is insecure.
     * Documentation here:
     * <a href="https://developer.android.com/training/articles/keystore#ImportingEncryptedKeys" />
     * describes how to import a key safely.
     *
     * @param storeType     KeyStore type, eg: "PKCS12"
     * @param storeStream   An InputStream from the keystore
     * @param storePassword The keystore password
     * @param extAlias      The alias, in the external keystore, of the entry to be imported.
     * @param extKeyPass    The key password
     * @param newAlias      The alias for the imported key
     * @throws KeyStoreException           on failure to create keystore
     * @throws CertificateException        on failure to load keystore
     * @throws NoSuchAlgorithmException    on failure to load keystore
     * @throws IOException                 on failure to load keystore
     * @throws UnrecoverableEntryException on failure to load keystore entry
     */
    public static void importEntry(
        @NonNull String storeType,
        @NonNull InputStream storeStream,
        @Nullable char[] storePassword,
        @NonNull String extAlias,
        @Nullable char[] extKeyPass,
        @NonNull String newAlias)
        throws KeyStoreException, CertificateException, NoSuchAlgorithmException,
        IOException, UnrecoverableEntryException {

        final KeyStore androidStore = KeyStore.getInstance(ANDROID_KEY_STORE);
        androidStore.load(null);

        final KeyStore.ProtectionParameter protectionParameter = (extKeyPass == null)
            ? null
            : new KeyStore.PasswordProtection(extKeyPass);

        final KeyStore externalStore = KeyStore.getInstance(storeType);
        externalStore.load(storeStream, storePassword);

        // this line of code will log an exception.
        androidStore.setEntry(newAlias, externalStore.getEntry(extAlias, protectionParameter), null);
    }
}
