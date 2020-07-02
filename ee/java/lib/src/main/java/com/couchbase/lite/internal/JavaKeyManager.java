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

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Date;

import com.couchbase.lite.LogDomain;
import com.couchbase.lite.internal.support.Log;


public class JavaKeyManager extends KeyManager {

    @Nullable
    @Override
    public KeyPair generateKeyPair(
        @NonNull String alias,
        @NonNull KeyAlgorithm algorithm,
        @NonNull KeySize keySize,
        @NonNull BigInteger serial,
        @NonNull Date expiration) {
        try { return KeyPairGenerator.getInstance("RSA").generateKeyPair(); }
        catch (NoSuchAlgorithmException ignore) { }

        return null;
    }

    @NonNull
    @Override
    public byte[] getKeyData(long keyToken) {
        Log.d(LogDomain.LISTENER, "### getKeyData @" + keyToken);
        return new byte[0];
    }

    @NonNull
    @Override
    public byte[] decrypt(long keyToken, @NonNull byte[] data) {
        Log.d(LogDomain.LISTENER, "### decrypt @" + keyToken);
        return new byte[0];
    }

    @NonNull
    @Override
    public byte[] signKey(long keyToken, int digestAlgorithm, @NonNull byte[] data) {
        Log.d(LogDomain.LISTENER, "### signKey @" + keyToken);
        return new byte[0];
    }

    @Override
    public void free(long keyToken) {
        Log.d(LogDomain.LISTENER, "### free @" + keyToken);
    }
}
