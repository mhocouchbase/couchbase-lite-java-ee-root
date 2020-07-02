//
// Copyright (c) 2020 Couchbase, Inc All rights reserved.
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
package com.couchbase.lite.internal;

import android.content.Context;
import android.os.Build;
import android.security.KeyPairGeneratorSpec;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.math.BigInteger;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.spec.RSAKeyGenParameterSpec;
import java.util.Date;

import javax.security.auth.x500.X500Principal;


public class AndroidKeyManager extends KeyManager {
    private final Context context;

    public AndroidKeyManager(@NonNull Context context) { this.context = context; }

    @Nullable
    @Override
    public KeyPair generateKeyPair(
        @NonNull String alias,
        @NonNull KeyAlgorithm algorithm,
        @NonNull KeySize keySize,
        @NonNull BigInteger serial,
        @NonNull Date expiration) {
        final RSAKeyGenParameterSpec algorithmSpec
            = new RSAKeyGenParameterSpec(keySize.getLen(), RSAKeyGenParameterSpec.F4);
        final X500Principal subject = new X500Principal("CN=" + alias);
        final Date now = new Date();

        try {
            final KeyPairGenerator keyFactory = KeyPairGenerator.getInstance("RSA", "AndroidKeyStore");

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                keyFactory.initialize(new KeyPairGeneratorSpec.Builder(context)
                    .setAlias(alias)
                    .setAlgorithmParameterSpec(algorithmSpec)
                    .setSerialNumber(serial)
                    .setSubject(subject)
                    .setStartDate(now)
                    .setEndDate(expiration)
                    .build());
            }
            else {
                keyFactory.initialize(new KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(algorithmSpec)
                    .setCertificateSerialNumber(serial)
                    .setCertificateSubject(subject)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setCertificateNotBefore(now)
                    .setCertificateNotAfter(expiration)
                    .setUserAuthenticationRequired(false)
                    .build());
            }

            return keyFactory.generateKeyPair();
        }
        catch (NoSuchAlgorithmException | NoSuchProviderException | InvalidAlgorithmParameterException ignore) { }

        return null;
    }

    @NonNull
    @Override
    public byte[] getKeyData(long keyToken) {
        android.util.Log.d("###", "getKeyData @" + keyToken);
        return new byte[0];
    }

    @NonNull
    @Override
    public byte[] decrypt(long keyToken, @NonNull byte[] data) {
        android.util.Log.d("###", "decrypt @" + keyToken);
        return new byte[0];
    }

    @NonNull
    @Override
    public byte[] signKey(long keyToken, int digestAlgorithm, @NonNull byte[] data) {
        android.util.Log.d("###", "signKey @" + keyToken);
        return new byte[0];
    }

    @Override
    public void free(long keyToken) {
        android.util.Log.d("###", "free @" + keyToken);
    }
}
