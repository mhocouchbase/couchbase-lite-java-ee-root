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
package com.couchbase.lite;

import com.couchbase.lite.internal.CouchbaseLiteInternal;
import com.couchbase.lite.internal.core.C4Database;
import com.couchbase.lite.internal.utils.Preconditions;


/**
 * Create a database with a given C4Database object in "shell" mode. The life of the
 * C4Database object will be managed by the caller. This is used to create a
 * Dictionary as an input of the predict() method of the PredictiveModel.
 */
class ShellDb extends BaseDatabase {
    protected ShellDb(long c4dbHandle) {
        Preconditions.assertNotZero(c4dbHandle, "db handle");
        CouchbaseLiteInternal.requireInit("Cannot create database");
        setC4DatabaseLocked(C4Database.getUnmanagedDatabase(c4dbHandle));
    }
}
