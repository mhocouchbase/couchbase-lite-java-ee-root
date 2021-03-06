//
// Copyright (c) 2020, 2019 Couchbase, Inc.  All rights reserved.
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
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

import com.couchbase.lite.internal.DbContext;
import com.couchbase.lite.internal.core.C4Prediction;
import com.couchbase.lite.internal.core.C4PredictiveModel;
import com.couchbase.lite.internal.exec.ClientTask;
import com.couchbase.lite.internal.fleece.FLEncoder;
import com.couchbase.lite.internal.fleece.FLSliceResult;
import com.couchbase.lite.internal.fleece.FLValue;
import com.couchbase.lite.internal.fleece.MRoot;
import com.couchbase.lite.internal.support.Log;
import com.couchbase.lite.internal.utils.Preconditions;


/**
 * <b>ENTERPRISE EDITION API</b><br><br>
 * <p>
 * The prediction model manager for registering and unregistering predictive models.
 */
public final class Prediction {
    private static class C4PredictiveModelImpl implements C4PredictiveModel {
        @NonNull
        private final PredictiveModel model;

        C4PredictiveModelImpl(@NonNull PredictiveModel model) { this.model = model; }

        // This method is called by reflection.  Don't change its signature.
        @Override
        public long predict(long input, long c4db) {
            final ClientTask<Dictionary> task = new ClientTask<>(() -> model.predict(
                (Dictionary) new MRoot(new DbContext(new ShellDb(c4db)), new FLValue(input), false).asNative()));
            task.execute();

            final Dictionary prediction;
            final Exception err = task.getFailure();
            if (err == null) { prediction = task.getResult(); }
            else {
                prediction = null;
                Log.w(LogDomain.QUERY, "Prediction model failed", err);
            }
            return encode(prediction).getHandle();
        }

        @NonNull
        private FLSliceResult encode(@Nullable Dictionary prediction) {
            if (prediction != null) {
                try (FLEncoder encoder = FLEncoder.getManagedEncoder()) {
                    prediction.encodeTo(encoder);
                    return encoder.finish2Unmanaged(); // Will be freed by the native code.
                }
                catch (LiteCoreException e) { Log.w(LogDomain.QUERY, "Failed encoding a predictive result", e); }
            }

            return FLSliceResult.getUnmanagedSliceResult(); // Will be freed by the native code.
        }
    }

    //---------------------------------------------
    // Member variables
    //---------------------------------------------

    private Map<String, Object> models;

    //---------------------------------------------
    // Constructor
    //---------------------------------------------

    Prediction() { }

    //---------------------------------------------
    // API - public methods
    //---------------------------------------------

    /**
     * Register a predictive model by the given name.
     *
     * @param name  The name of the predictive model.
     * @param model The predictive model.
     */
    public synchronized void registerModel(@NonNull String name, @NonNull PredictiveModel model) {
        Preconditions.assertNotNull(name, "name");
        Preconditions.assertNotNull(model, "model");

        if (models == null) { models = new HashMap<>(); }

        if (models.containsKey(name)) { C4Prediction.unregister(name); }

        final C4PredictiveModel c4Model = new C4PredictiveModelImpl(model);
        C4Prediction.register(name, c4Model);
        models.put(name, c4Model);
    }

    /**
     * Unregister the predictive model of the given name.
     *
     * @param name The name of the predictive model.
     */
    public synchronized void unregisterModel(@NonNull String name) {
        Preconditions.assertNotNull(name, "name");

        if (models == null) { return; }

        C4Prediction.unregister(name);
        models.remove(name);
    }
}
