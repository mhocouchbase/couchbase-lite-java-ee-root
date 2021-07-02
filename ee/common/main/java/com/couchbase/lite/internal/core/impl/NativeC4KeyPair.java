//
// Copyright (c) 2020 Couchbase, Inc All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// https://info.couchbase.com/rs/302-GJY-034/images/2017-10-30_License_Agreement.pdf
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
package com.couchbase.lite.internal.core.impl;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.couchbase.lite.LiteCoreException;
import com.couchbase.lite.internal.core.C4KeyPair;


/**
 * The C4Listener companion object
 */
public class NativeC4KeyPair implements C4KeyPair.NativeImpl {
    @Override
    public long nFromExternal(byte algorithm, int keySizeInBits, long context) {
        return fromExternal(algorithm, keySizeInBits, context);
    }

    @NonNull
    @Override
    public byte[] nGenerateSelfSignedCertificate(
        long c4KeyPair,
        byte algorithm,
        int keyBits,
        @Nullable String[][] subjectName,
        byte usage,
        long validityInSeconds)
        throws LiteCoreException {
        return generateSelfSignedCertificate(c4KeyPair, algorithm, keyBits, subjectName, usage, validityInSeconds);
    }

    @Override
    public void nFree(long hdl) { free(hdl); }


    //-------------------------------------------------------------------------
    // Native Methods
    //-------------------------------------------------------------------------

    @NonNull
    private static native byte[] generateSelfSignedCertificate(
        long c4KeyPair,
        byte algorithm,
        int keyBits,
        @Nullable String[][] nameComponents,
        byte usage,
        long validityInSeconds)
        throws LiteCoreException;

    private static native long fromExternal(byte algorithm, int keySizeInBits, long context);

    private static native void free(long handle);
}
