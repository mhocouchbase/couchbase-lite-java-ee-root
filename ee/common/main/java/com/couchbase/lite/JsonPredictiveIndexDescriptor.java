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

import java.util.ArrayList;
import java.util.List;

import com.couchbase.lite.internal.core.IndexType;
import com.couchbase.lite.internal.utils.Preconditions;


class JsonPredictiveIndexDescriptor extends AbstractJsonIndexDescriptor {
    @NonNull
    private final String model;
    @NonNull
    private final Expression input;
    @Nullable
    private final List<String> properties;

    JsonPredictiveIndexDescriptor(
        @NonNull String model,
        @NonNull Expression input, @Nullable List<String> properties) {
        super(IndexType.PREDICTIVE);
        this.model = Preconditions.assertNotNull(model, "model");
        this.input = Preconditions.assertNotNull(input, "input");
        this.properties = properties;
    }

    @NonNull
    @Override
    List<Object> getJSON() {
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
