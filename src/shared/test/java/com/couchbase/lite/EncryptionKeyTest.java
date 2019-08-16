//
// EncryptionKeyTest.java
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

import org.junit.Test;

import com.couchbase.lite.internal.core.C4Constants;
import com.couchbase.lite.utils.Report;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;


public class EncryptionKeyTest extends BaseTest {
    @Test
    public void testDerivePBKDF2SHA256Key() {
        EncryptionKey key = new EncryptionKey("hello world!");
        assertNotNull(key.getKey());
        assertEquals(C4Constants.EncryptionKeySize.AES256, key.getKey().length);
        Report.log(LogLevel.INFO, "key -> " + bytesToHex(key.getKey()));
    }

    @Test
    public void testInvalidKey() {
        try {
            new EncryptionKey((byte[]) null);
            fail();
        }
        catch (IllegalArgumentException e) {
            assertEquals("key cannot be null.", e.getMessage());
        }
        try {
            new EncryptionKey("a".getBytes());
            fail();
        }
        catch (IllegalArgumentException e) {
            assertEquals("Key size is invalid. Key must be a 256-bit (32-byte) key.", e.getMessage());
        }
    }

    private String bytesToHex(byte[] in) {
        final StringBuilder builder = new StringBuilder();
        for (byte b : in) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }

}
