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

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.Executor

/**
 * A Flow of message endpoint state changes.
 *
 * @param executor Optional executor on which to run the change listener: default is the main thread
 *
 * @see com.couchbase.lite.MessageEndpointListener.addChangeListener
 */
@ExperimentalCoroutinesApi
fun MessageEndpointListener.messageEndpointChangeFlow(executor: Executor? = null) = callbackFlow {
    val token = this@messageEndpointChangeFlow.addChangeListener(executor) { trySend(it) }
    awaitClose { this@messageEndpointChangeFlow.removeChangeListener(token) }
}
