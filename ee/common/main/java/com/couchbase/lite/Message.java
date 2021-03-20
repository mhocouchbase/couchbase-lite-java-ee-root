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

import android.support.annotation.NonNull;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import com.couchbase.lite.internal.utils.Preconditions;


/**
 * <b>ENTERPRISE EDITION API</b><br><br>
 * <p>
 * A message sent between message endpoint connections.
 */
public class Message {
    /**
     * Creates a message object from data.
     *
     * @param data the data
     * @return the Message object
     */
    @NonNull
    public static Message fromData(@NonNull byte[] data) {
        Preconditions.assertNotNull(data, "data");
        return new Message(data);
    }


    private final byte[] data;

    // !!! This method stores a mutable array as private data
    @SuppressFBWarnings("EI_EXPOSE_REP")
    @SuppressWarnings("PMD.ArrayIsStoredDirectly")
    Message(byte[] data) { this.data = data; }

    /**
     * Gets the message as data.
     * <p>
     *
     * @return the data
     */
    // !!! This method returns a writable copy of its private data
    @SuppressFBWarnings("EI_EXPOSE_REP")
    @SuppressWarnings("PMD.MethodReturnsInternalArray")
    @NonNull
    public byte[] toData() { return this.data; }
}
