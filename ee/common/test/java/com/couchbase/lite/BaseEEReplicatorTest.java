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

public abstract class BaseEEReplicatorTest extends BaseReplicatorTest {
    protected final ReplicatorConfiguration pushConfig() { return makeConfigTargetingOtherDb(true, false); }

    protected final ReplicatorConfiguration pullConfig() { return makeConfigTargetingOtherDb(false, true); }

    protected final ReplicatorConfiguration makeConfigTargetingOtherDb(boolean push, boolean pull) {
        return makeConfigTargetingOtherDb(push, pull, null);
    }

    protected final ReplicatorConfiguration makeConfigTargetingOtherDb(
        boolean push,
        boolean pull,
        ConflictResolver resolver) {
        return makeConfig(baseTestDb, new DatabaseEndpoint(otherDB), push, pull, false, null, resolver);
    }
}
