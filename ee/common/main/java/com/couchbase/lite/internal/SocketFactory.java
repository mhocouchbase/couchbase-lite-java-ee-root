//
// Copyright (c) 2020, 2019 Couchbase, Inc All rights reserved.
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
package com.couchbase.lite.internal;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.security.cert.Certificate;
import java.util.List;

import com.couchbase.lite.MessageEndpoint;
import com.couchbase.lite.ProtocolType;
import com.couchbase.lite.ReplicatorConfiguration;
import com.couchbase.lite.internal.replicator.CBLCookieStore;
import com.couchbase.lite.internal.replicator.MessageSocket;
import com.couchbase.lite.internal.sockets.SocketFromCore;
import com.couchbase.lite.internal.sockets.SocketToCore;
import com.couchbase.lite.internal.utils.Fn;


public class SocketFactory extends AbstractSocketFactory {
    public SocketFactory(
        @NonNull ReplicatorConfiguration config,
        @NonNull CBLCookieStore cookieStore,
        @NonNull Fn.Consumer<List<Certificate>> serverCertsListener) {
        super(config, cookieStore, serverCertsListener);
    }

    @Nullable
    protected SocketFromCore createPlatformSocket(@NonNull SocketToCore coreDelegate) {
        if (!(endpoint instanceof MessageEndpoint)) { return null; }
        final MessageEndpoint endpt = (MessageEndpoint) endpoint;
        return MessageSocket.create(
            coreDelegate,
            endpt.getDelegate().createConnection(endpt),
            ProtocolType.getFramingForProtocol(endpt.getProtocolType()));
    }
}
