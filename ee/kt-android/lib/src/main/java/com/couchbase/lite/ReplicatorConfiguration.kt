//
// Copyright (c) 2021 Couchbase, Inc All rights reserved.
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
package com.couchbase.lite

val ReplicatorConfigurationFactory: ReplicatorConfiguration? = null
fun ReplicatorConfiguration?.create(
    database: Database? = null,
    target: Endpoint? = null,
    type: ReplicatorType? = null,
    continuous: Boolean? = false,
    authenticator: Authenticator,
    headers: Map<String, String>,
    pinnedServerCertificate: ByteArray,
    channels: List<String>,
    documentIDs: List<String>,
    pushFilter: ReplicationFilter,
    pullFilter: ReplicationFilter,
    conflictResolver: ConflictResolver,
    maxRetries: Int,
    maxRetryWaitTime: Int,
    heartbeat: Int,
    enableAutoPurge: Boolean,
    acceptOnlySelfSignedServerCertificate: Boolean
) = ReplicatorConfiguration(
    database ?: this?.database ?: error("DB is missing"),
    target ?:
    this?.target ?: error("Target is missing"),
    type ?: this?.type ?: ReplicatorType.PUSH_AND_PULL,
    continuous ?: this?.isContinuous() ?: false,
    // …
    maxRetries ?: this?.maxRetries ?: -1,
    // …
)
