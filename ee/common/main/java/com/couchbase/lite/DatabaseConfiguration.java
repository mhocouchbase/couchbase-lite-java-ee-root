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

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;


/**
 * Configuration for opening a database.
 */
public final class DatabaseConfiguration extends AbstractDatabaseConfiguration {

    //---------------------------------------------
    // member variables
    //---------------------------------------------

    private EncryptionKey encryptionKey;

    //---------------------------------------------
    // Constructors
    //---------------------------------------------

    public DatabaseConfiguration() { this(null); }

    public DatabaseConfiguration(@Nullable DatabaseConfiguration config) { this(config, false); }

    DatabaseConfiguration(@Nullable DatabaseConfiguration config, boolean readOnly) {
        super(config, readOnly);
        this.encryptionKey = (config == null) ? null : config.encryptionKey;
    }

    //---------------------------------------------
    // API - public methods
    //---------------------------------------------

    /**
     * <b>ENTERPRISE EDITION API</b><br><br>
     * <p>
     * Set a key to encrypt the database with. If the database does not exist and is being created,
     * it will use this key, and the same key must be given every time it's opened
     *
     * @param encryptionKey the key
     * @return The self object.
     */
    @NonNull
    public DatabaseConfiguration setEncryptionKey(EncryptionKey encryptionKey) {
        verifyWritable();
        this.encryptionKey = encryptionKey;
        return this;
    }

    /**
     * <b>ENTERPRISE EDITION API</b><br><br>
     * <p>
     * Returns a key to encrypt the database with.
     *
     * @return the key
     */
    public EncryptionKey getEncryptionKey() { return encryptionKey; }

    @Override
    protected DatabaseConfiguration getDatabaseConfiguration() { return this; }
}
