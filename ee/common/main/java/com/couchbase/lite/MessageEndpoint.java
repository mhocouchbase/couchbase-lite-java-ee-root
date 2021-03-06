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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.couchbase.lite.internal.utils.Preconditions;


/**
 * <b>ENTERPRISE EDITION API</b><br><br>
 * <p>
 * Replication Message endpoint.
 */
public class MessageEndpoint implements Endpoint {
    // Unique ID required for calculating remote checkpoint id
    @NonNull
    private final String uid;

    // Any object identifying the connecting peer
    private final Object target;

    // Protocol type of transmission
    @NonNull
    private final ProtocolType protocolType;

    // Delegate
    @NonNull
    private final MessageEndpointDelegate delegate;

    /**
     * Initializes a CBLMessageEndpoint object.
     *
     * @param uid          the unique identifier of the endpoint
     * @param target       an optional arbitrary object that represents the endpoint
     * @param protocolType the data transportation protocol
     * @param delegate     the delegate for creating MessageEndpointConnection objects
     */
    public MessageEndpoint(
        @NonNull String uid,
        @Nullable Object target,
        @NonNull ProtocolType protocolType,
        @NonNull MessageEndpointDelegate delegate) {
        Preconditions.assertNotNull(uid, "uid");
        Preconditions.assertNotNull(protocolType, "protocolType");
        Preconditions.assertNotNull(delegate, "delegate");

        this.uid = uid;
        this.target = target;
        this.protocolType = protocolType;
        this.delegate = delegate;
    }

    //-------------------------------------------------------------------------
    // Setters and Getters
    //-------------------------------------------------------------------------

    /**
     * Gets the unique identifier of the endpoint.
     *
     * @return the unique identifier of the endpoint
     */
    @NonNull
    public String getUid() { return uid; }

    /**
     * Gets the target object which is an arbitrary object that represents the endpoint.
     *
     * @return the target object.
     */
    @NonNull
    public Object getTarget() { return target; }

    /**
     * Gets the data transportation protocol of the endpoint.
     *
     * @return the data transportation protocol
     */
    @NonNull
    public ProtocolType getProtocolType() { return protocolType; }

    /**
     * Gets the delegate object used for creating MessageEndpointConnection objects.
     *
     * @return the delegate object.
     */
    @NonNull
    public MessageEndpointDelegate getDelegate() { return delegate; }

    @NonNull
    @Override
    public String toString() { return "MessageEndpoint{" + uid + ", " + protocolType + ", " + target + "}"; }
}
