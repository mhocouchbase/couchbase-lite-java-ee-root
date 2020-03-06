//
// DatabaseEndpoint.java
//
// Copyright (c) 2018 Couchbase, Inc.  All rights reserved.
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

import com.couchbase.lite.internal.utils.Preconditions;


/**
 * <b>ENTERPRISE EDITION API</b><br><br>
 * <p>
 * Database based replication target endpoint.
 */
public final class DatabaseEndpoint implements Endpoint {
    private final Database database;

    /**
     * Constructor with the database instance
     *
     * @param database the target database
     */
    public DatabaseEndpoint(@NonNull Database database) {
        this.database = Preconditions.assertNotNull(database, "database");
    }

    /**
     * Return the Database instance
     */
    @NonNull
    public Database getDatabase() { return database; }

    @NonNull
    @Override
    public String toString() { return "DatabaseEndpoint{database=" + database + '}'; }
}
