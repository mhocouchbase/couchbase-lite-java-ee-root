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
    continuous: Boolean? = null,
    authenticator: Authenticator? = null,
    headers: Map<String, String>? = null,
    pinnedServerCertificate: ByteArray? = null,
    channels: List<String>? = null,
    documentIDs: List<String>? = null,
    pushFilter: ReplicationFilter? = null,
    pullFilter: ReplicationFilter? = null,
    conflictResolver: ConflictResolver? = null,
    maxAttempts: Int? = null,
    maxAttemptWaitTime: Int? = null,
    heartbeat: Int? = null,
    enableAutoPurge: Boolean? = null,
    acceptOnlySelfSignedServerCertificate: Boolean? = null
) = ReplicatorConfiguration(
    database ?: this?.database ?: error("Must specify a database"),
    type ?: this?.type ?: ReplicatorType.PUSH_AND_PULL,
    continuous ?: this?.isContinuous ?: false,
    authenticator ?: this?.authenticator,
    headers ?: this?.headers,
    pinnedServerCertificate ?: this?.pinnedServerCertificate,
    channels ?: this?.channels,
    documentIDs ?: this?.documentIDs,
    pushFilter ?: this?.pushFilter,
    pullFilter ?: this?.pullFilter,
    conflictResolver ?: this?.conflictResolver,
    maxAttempts ?: this?.maxAttempts ?: 0,
    maxAttemptWaitTime ?: this?.maxAttemptWaitTime ?: 0,
    heartbeat ?: this?.heartbeat ?: 0,
    enableAutoPurge ?: this?.isAutoPurgeEnabled ?: false,
    target ?: this?.target ?: error("Must specify a target"),
    acceptOnlySelfSignedServerCertificate ?: this?.isAcceptOnlySelfSignedServerCertificate ?: false
)

val MessageEndpointListenerConfigurationFactory: MessageEndpointListenerConfiguration? = null
fun MessageEndpointListenerConfiguration?.create(
    database: Database? = null,
    protocolType: ProtocolType? = null
) = MessageEndpointListenerConfiguration(
    database ?: this?.database ?: error("Must specify a database"),
    protocolType ?: this?.protocolType ?: error("Must specify a protocol"),
)

val URLEndpointListenerConfigurationFactory: URLEndpointListenerConfiguration? = null
fun URLEndpointListenerConfiguration?.create(
    database: Database? = null,
    networkInterface: String? = null,
    port: Int? = null,
    disableTls: Boolean? = null,
    identity: TLSIdentity? = null,
    authenticator: ListenerAuthenticator? = null,
    readOnly: Boolean? = null,
    enableDeltaSync: Boolean? = null,
) = URLEndpointListenerConfiguration(
    database ?: this?.database ?: error("Must specify a database"),
    networkInterface ?: this?.networkInterface,
    port ?: this?.port ?: error("Must specify a port"),
    disableTls ?: this?.isTlsDisabled ?: false,
    identity ?: this?.tlsIdentity,
    authenticator ?: this?.authenticator,
    readOnly ?: this?.isReadOnly ?: false,
    enableDeltaSync ?: this?.isDeltaSyncEnabled ?: false
)

val DatabaseConfigurationFactory: DatabaseConfiguration? = null
fun DatabaseConfiguration?.create(
    databasePath: String? = null,
    encryptionKey: EncryptionKey? = null
) = DatabaseConfiguration(
    databasePath ?: this?.directory,
    encryptionKey ?: this?.encryptionKey
)

