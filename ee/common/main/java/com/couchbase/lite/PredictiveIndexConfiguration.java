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

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.util.List;

import com.couchbase.lite.internal.core.IndexType;

/**
 * <b>ENTERPRISE EDITION API</b><br><br>
 * <p>
 * The predictive index used for querying with prediction function. The predictive index
 * is different from the normal index in that the predictive index will cache the prediction
 * result along with creating the value index of the prediction output properties.
 * <p>
 * The PredictiveIndex can be created by using IndexBuilder's predictiveIndex() function.
 * If the prediction output properties are not specified, the predictive index will only cache
 * the predictive result so that the predictive model will not be called again after indexing.
 */
public interface PredictiveIndexConfiguration {
    final class N1qlPredictiveIndexConfiguration
        extends AbstractN1qlIndexDescriptor implements PredictiveIndexConfiguration {
        N1qlPredictiveIndexConfiguration(@NonNull String n1ql) { super(IndexType.PREDICTIVE, n1ql); }
    }

    final class JsonPredictiveIndexConfiguration
        extends JsonPredictiveIndexDescriptor implements PredictiveIndexConfiguration {
        JsonPredictiveIndexConfiguration(
            @NonNull String model,
            @NonNull Expression input,
            @Nullable List<String> properties) {
            super(model, input, properties);
        }
    }

    static PredictiveIndexConfiguration init(@NonNull String n1ql) {
        return new N1qlPredictiveIndexConfiguration(n1ql);
    }

    static PredictiveIndexConfiguration init(
        @NonNull String model,
        @NonNull Expression input,
        @Nullable List<String> properties) {
        return new JsonPredictiveIndexConfiguration(model, input, properties);
    }
}
