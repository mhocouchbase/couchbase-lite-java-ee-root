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


/**
 * The {@link MessageEndpointListener} change listener protocol.
 */
@FunctionalInterface
public interface MessageEndpointListenerChangeListener extends ChangeListener<MessageEndpointListenerChange> {
    @Override
    void changed(@NonNull MessageEndpointListenerChange change);
}
