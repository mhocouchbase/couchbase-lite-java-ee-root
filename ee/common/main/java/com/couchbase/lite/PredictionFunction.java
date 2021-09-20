//
// Copyright (c) 2020, 2019 Couchbase, Inc.  All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
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

import java.util.Arrays;
import java.util.List;

import com.couchbase.lite.internal.utils.Preconditions;


/**
 * <b>ENTERPRISE EDITION API</b><br><br>
 * <p>
 * PredictionFunction that allows to create an expression that
 * refers to one of the properties of the prediction result dictionary.
 */
public final class PredictionFunction extends Expression {

    //---------------------------------------------
    // member variables
    //---------------------------------------------

    @NonNull
    private final Expression model;

    @Nullable
    private final Expression input;

    //---------------------------------------------
    // Constructor
    //---------------------------------------------

    PredictionFunction(@Nullable String model, @Nullable Expression input) {
        Preconditions.assertNotNull(model, "model");
        Preconditions.assertNotNull(input, "input");

        this.model = Expression.string(model);
        this.input = input;
    }

    //---------------------------------------------
    // API - public methods
    //---------------------------------------------

    /**
     * Creates a property expression that refers to a property of the prediction result dictionary.
     *
     * @param path The path to the property.
     * @return The property expression referring to a property of the prediction dictionary result.
     */
    @NonNull
    public Expression propertyPath(@NonNull String path) {
        Preconditions.assertNotNull(path, "path");
        return getPredictionFunction(Arrays.asList(model, input, Expression.string("." + path)));
    }

    //---------------------------------------------
    // Package level access
    //---------------------------------------------

    @NonNull
    @Override
    Object asJSON() { return getPredictionFunction(Arrays.asList(model, input)).asJSON(); }

    //---------------------------------------------
    // Private level access
    //---------------------------------------------

    @NonNull
    FunctionExpression getPredictionFunction(@NonNull List<Expression> params) {
        return new FunctionExpression("PREDICTION()", params);
    }
}
