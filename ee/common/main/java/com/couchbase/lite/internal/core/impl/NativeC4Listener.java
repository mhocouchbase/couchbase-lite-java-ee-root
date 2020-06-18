//
// Copyright (c) 2020 Couchbase, Inc All rights reserved.
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
package com.couchbase.lite.internal.core.impl;

import java.util.List;

import com.couchbase.lite.ConnectionStatus;
import com.couchbase.lite.LiteCoreException;
import com.couchbase.lite.internal.core.C4Listener;


/**
 * The C4Listener companion object
 */
public class NativeC4Listener implements C4Listener.NativeImpl {

    @SuppressWarnings("PMD.ExcessiveParameterList")
    @Override
    public long nStartHttp(
        long context,
        int port,
        String iFace,
        String dbPath,
        boolean create,
        boolean delete,
        boolean push,
        boolean pull,
        boolean deltaSync)
        throws LiteCoreException {
        return startHttp(port, iFace, context, dbPath, create, delete, push, pull, deltaSync);
    }

    @SuppressWarnings("PMD.ExcessiveParameterList")
    @Override
    public long nStartTls(
        long context,
        int port,
        String iFace,
        String dbPath,
        boolean create,
        boolean delete,
        boolean push,
        boolean pull,
        boolean deltaSync,
        byte[] cert,
        boolean requireClientCerts,
        byte[] rootClientCerts)
        throws LiteCoreException {
        return startTls(
            port,
            iFace,
            context,
            cert,
            requireClientCerts,
            rootClientCerts,
            dbPath,
            create,
            delete,
            push,
            pull,
            deltaSync);
    }

    @Override
    public void nFree(long handle) { free(handle); }

    @Override
    public void nShareDb(long handle, String name, long c4Db) throws LiteCoreException { shareDb(handle, name, c4Db); }

    @Override
    public void nUnshareDb(long handle, long c4Db) throws LiteCoreException { unshareDb(handle, c4Db); }

    @Override
    public List<String> nGetUrls(long handle, long c4Db) throws LiteCoreException { return getUrls(handle, c4Db); }

    @Override
    public int nGetPort(long handle) { return getPort(handle); }

    @Override
    public ConnectionStatus nGetConnectionStatus(long handle) { return getConnectionStatus(handle); }

    @Override
    public String nGetUriFromPath(long handle, String path) { return getUriFromPath(handle, path); }

    @SuppressWarnings("PMD.ExcessiveParameterList")
    private static native long startHttp(
        int port,
        String networkInterface,
        long context,
        String dbPath,
        boolean allowCreateDBs,
        boolean allowDeleteDBs,
        boolean allowPush,
        boolean allowPull,
        boolean enableDeltaSync)
        throws LiteCoreException;

    @SuppressWarnings("PMD.ExcessiveParameterList")
    private static native long startTls(
        int port,
        String networkInterface,
        long context,
        byte[] cert,
        boolean requireClientCerts,
        byte[] rootClientCerts,
        String dbPath,
        boolean allowCreateDBs,
        boolean allowDeleteDBs,
        boolean allowPush,
        boolean allowPull,
        boolean enableDeltaSync)
        throws LiteCoreException;

    private static native void free(long handle);

    private static native void shareDb(long handle, String name, long c4Db) throws LiteCoreException;

    private static native void unshareDb(long handle, long c4Db) throws LiteCoreException;

    private static native List<String> getUrls(long handle, long c4Db) throws LiteCoreException;

    private static native int getPort(long handle);

    private static native ConnectionStatus getConnectionStatus(long handle);

    private static native String getUriFromPath(long handle, String path);
}
