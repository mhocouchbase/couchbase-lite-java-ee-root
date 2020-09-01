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
     * Imports the key entry including public key, private key, and certificates from the given
     * KeyStore input stream into the Android KeyStore. The imported key entry can be used as a
     * TLSIdentity by calling TLSIdentity.get(String alias) method.
     * <p>
     * NOTE:
     * The key data including the private key data will be temporarily in memory during the import operation.
     * * Android 9 (API 28) or higher has an alternative method to import keys more securely.
     * Check the documentation here:
     * <a href="https://developer.android.com/training/articles/keystore#ImportingEncryptedKeys" />
     * for more info.
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
        final KeyStore.Entry newEntry = externalStore.getEntry(extAlias, protectionParameter);
        if (newEntry == null) {
            throw new KeyStoreException("There is no entry in this keystore for alias: " + newAlias);
        }

        androidStore.setEntry(newAlias, newEntry, null);
    }
}
