//
// Copyright (c) 2020, 2017 Couchbase, Inc All rights reserved.
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
package com.couchbase.lite;


import androidx.annotation.NonNull;


public abstract class BaseEEReplicatorTest extends BaseReplicatorTest {
    // here because getProtocolType is only visible in this package
    public static ProtocolType getListenerProtocol(@NonNull MessageEndpointListener listener) {
        return listener.getConfig().getProtocolType();
    }

    protected final ReplicatorConfiguration pushConfig() {
        return makeConfigTargetingOtherDb(ReplicatorType.PUSH);
    }

    protected final ReplicatorConfiguration pullConfig() {
        return makeConfigTargetingOtherDb(ReplicatorType.PULL);
    }

    protected final ReplicatorConfiguration makeConfigTargetingOtherDb(ReplicatorType type) {
        return makeConfigTargetingOtherDb(type, null);
    }

    protected final ReplicatorConfiguration makeConfigTargetingOtherDb(ReplicatorType type, ConflictResolver rsolv) {
        return makeConfig(baseTestDb, new DatabaseEndpoint(otherDB), type, false, null, rsolv);
    }
}
