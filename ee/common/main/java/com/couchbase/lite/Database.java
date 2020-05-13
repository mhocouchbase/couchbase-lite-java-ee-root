//
// Database.java
//
// Copyright (c) 2018 Couchbase, Inc.  All rights reserved.
//
// Licensed under the Couchbase License Agreement (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at:
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

import java.io.File;

import com.couchbase.lite.internal.CBLStatus;
import com.couchbase.lite.internal.core.C4Constants;
import com.couchbase.lite.internal.core.C4Database;
import com.couchbase.lite.internal.core.C4Replicator;
import com.couchbase.lite.internal.core.C4ReplicatorListener;
import com.couchbase.lite.internal.core.C4Socket;
import com.couchbase.lite.internal.utils.Preconditions;


/**
 * A Couchbase Lite database.
 */
public final class Database extends AbstractDatabase {
    /**
     * Make a copy of a database in a new location.
     *
     * @param path   path to the existing db file
     * @param name   the name of the new DB
     * @param config a config with the new location
     * @throws CouchbaseLiteException on copy failure
     */
    public static void copy(
        @NonNull File path,
        @NonNull String name,
        @NonNull DatabaseConfiguration config)
        throws CouchbaseLiteException {
        Preconditions.assertNotNull(path, "path");
        Preconditions.assertNotNull(name, "name");
        Preconditions.assertNotNull(config, "config");

        final EncryptionKey encryptionKey = config.getEncryptionKey();

        AbstractDatabase.copy(
            path,
            name,
            config,
            getEncryptionAlgorithm(encryptionKey),
            getEncryptionKey(encryptionKey));
    }

    private static byte[] getEncryptionKey(EncryptionKey key) { return (key == null) ? null : key.getKey(); }

    private static int getEncryptionAlgorithm(EncryptionKey key) {
        return (getEncryptionKey(key) == null)
            ? C4Constants.EncryptionAlgorithm.NONE
            : C4Constants.EncryptionAlgorithm.AES256;
    }

    /**
     * <b>ENTERPRISE EDITION API</b><br><br>
     * <p>
     * The predictive model manager for registering and unregistering predictive models.
     * This is part of the Public API.
     */
    @SuppressWarnings({"PMD.FieldNamingConventions", "ConstantName"})
    @NonNull
    public static final Prediction prediction = new Prediction();

    //---------------------------------------------
    // Constructors
    //---------------------------------------------

    /**
     * Construct a Database with a given name and the default config.
     * If the database does not yet exist it will be created.
     *
     * @param name The name of the database: May NOT contain capital letters!
     * @throws CouchbaseLiteException if any error occurs during the open operation.
     */
    public Database(@NonNull String name) throws CouchbaseLiteException { super(name, new DatabaseConfiguration()); }

    /**
     * Construct a  AbstractDatabase with a given name and database config.
     * If the database does not yet exist, it will be created, unless the `readOnly` option is used.
     *
     * @param name   The name of the database: May NOT contain capital letters!
     * @param config The database config.
     * @throws CouchbaseLiteException Throws an exception if any error occurs during the open operation.
     */
    public Database(@NonNull String name, @NonNull DatabaseConfiguration config) throws CouchbaseLiteException {
        super(name, config);
    }

    Database(C4Database c4db) { super(c4db); }

    //---------------------------------------------
    // Public API
    //---------------------------------------------

    /**
     * <b>ENTERPRISE EDITION API</b><br><br>
     * <p>
     * Changes the database's encryption key, or removes encryption if the new key is null.
     *
     * @param encryptionKey The encryption key
     * @throws CouchbaseLiteException on error
     */
    public void changeEncryptionKey(EncryptionKey encryptionKey) throws CouchbaseLiteException {
        synchronized (getLock()) {
            try { getC4Database().rekey(getEncryptionAlgorithm(encryptionKey), getEncryptionKey(encryptionKey)); }
            catch (LiteCoreException e) { throw CBLStatus.convertException(e); }
        }
    }

    //---------------------------------------------
    // Package visible
    //---------------------------------------------

    // Implementation of abstract methods for Encryption
    @Override
    int getEncryptionAlgorithm() { return getEncryptionAlgorithm(config.getEncryptionKey()); }

    @Override
    byte[] getEncryptionKey() { return getEncryptionKey(config.getEncryptionKey()); }

    C4Replicator createTargetReplicator(
        @NonNull C4Socket openSocket,
        int push,
        int pull,
        @Nullable byte[] options,
        @Nullable C4ReplicatorListener listener,
        @NonNull Object replicatorContext)
        throws LiteCoreException {
        return getC4Database().createTargetReplicator(
            openSocket,
            push,
            pull,
            options,
            listener,
            replicatorContext);
    }
}
