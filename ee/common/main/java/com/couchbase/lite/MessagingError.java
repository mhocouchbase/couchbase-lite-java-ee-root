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

import com.couchbase.lite.internal.utils.Preconditions;


/**
 * <b>ENTERPRISE EDITION API</b><br><br>
 * <p>
 * The messaging error.
 */
public class MessagingError {
    @NonNull
    private final Exception error;
    private final boolean recoverable;

    /**
     * Creates a MessagingError with the given error and recoverable flag identifying
     * if the error is recoverable or not. The replicator uses recoverable
     * flag to determine whether the replication should be retried or stopped as the error
     * is non-recoverable.
     *
     * @param error       the error
     * @param recoverable the recoverable flag
     */
    public MessagingError(@NonNull Exception error, boolean recoverable) {
        Preconditions.assertNotNull(error, "error");
        this.error = error;
        this.recoverable = recoverable;
    }

    /**
     * Gets error object.
     *
     * @return the error object
     */
    @NonNull
    public Exception getError() { return error; }

    /**
     * Is the error recoverable?
     *
     * @return the recoverable flag identifying whether the error is recoverable or not
     */
    public boolean isRecoverable() { return recoverable; }

    @NonNull
    @Override
    public String toString() { return "MessagingError{" + recoverable + ", " + error + '}'; }
}
