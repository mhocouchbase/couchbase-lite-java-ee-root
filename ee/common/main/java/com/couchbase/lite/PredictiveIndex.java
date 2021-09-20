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

import java.util.ArrayList;
import java.util.List;

import com.couchbase.lite.internal.utils.Preconditions;


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
public class PredictiveIndex extends Index {
    @NonNull
    private final String model;
    @NonNull
    private final Expression input;
    @Nullable
    private final List<String> properties;

    PredictiveIndex(
        @NonNull String model,
        @NonNull Expression input,
        @Nullable List<String> properties) {
        super(IndexType.PREDICTIVE);
        this.model = Preconditions.assertNotNull(model, "model");
        this.input = Preconditions.assertNotNull(input, "input");
        this.properties = properties;
    }

    @NonNull
    @Override
    List<Object> getJson() {
        final List<Object> items = new ArrayList<>();
        items.add("PREDICTION()");
        items.add(model);
        items.add(input.asJSON());

        if (properties != null) {
            for (String property: properties) { items.add("." + property); }
        }

        final List<Object> json = new ArrayList<>();
        json.add(items);

        return json;
    }
}
