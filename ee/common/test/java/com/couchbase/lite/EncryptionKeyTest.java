//
// Copyright (c) 2020, 2018 Couchbase, Inc.  All rights reserved.
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

import java.nio.charset.StandardCharsets;

import org.junit.Test;

import com.couchbase.lite.internal.core.C4Constants;
import com.couchbase.lite.internal.utils.Report;

import static org.junit.Assert.*;


public class EncryptionKeyTest extends BaseTest {
    @Test
    public void testDerivePBKDF2SHA256Key() {
        EncryptionKey key = new EncryptionKey("hello world!");
        assertNotNull(key.getKey());
        assertEquals(C4Constants.EncryptionKeySize.AES256, key.getKey().length);
        Report.log(LogLevel.INFO, "key -> " + bytesToHex(key.getKey()));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNullKey() { new EncryptionKey((byte[]) null); }

    @Test(expected = IllegalArgumentException.class)
    public void testShortKey() { new EncryptionKey("abc".getBytes(StandardCharsets.UTF_8)); }

    private String bytesToHex(byte[] in) {
        final StringBuilder builder = new StringBuilder();
        for (byte b : in) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }
}
