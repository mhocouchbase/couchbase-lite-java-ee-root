//
// Copyright (c) 2019 Couchbase, Inc.  All rights reserved.
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

import java.util.List;


/**
 * IndexBuilder used for building database index objects.
 */
public final class IndexBuilder extends AbstractIndexBuilder {

    /**
     * <b>ENTERPRISE EDITION API</b><br><br>
     * <p>
     * Create a predictive index with the given predictive model name, input specification to
     * the predictive model, and the properties of the prediction result.
     * <p>
     * The input given specification should be matched to the input specification given to the
     * query prediction() function so that the predictive index can be matched and used in query.
     * <p>
     * The predictive index is different from the normal index in that the predictive index will
     * also cache the prediction result along with creating the value index of the specified
     * properties. If the properties are not specified, the predictive index will only cache
     * the prediction result so that the prediction model will not be called again after indexing.
     * If multiple properties are specified, a compound value index will be created from
     * the given properties.
     *
     * @param model      The predictive model name.
     * @param input      The input specification that should be matched with the input
     *                   specification given to the query prediction function.
     * @param properties The prediction result's properties to be indexed.
     * @return The predictive index.
     */
    @NonNull
    public static PredictiveIndex predictiveIndex(
        @NonNull String model,
        @NonNull Expression input,
        List<String> properties) {
        return new PredictiveIndex(model, input, properties);
    }
}
